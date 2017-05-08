package com.wavefront.agent;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.google.common.util.concurrent.AtomicDouble;
import com.google.common.util.concurrent.RecyclableRateLimiter;
import com.google.gson.Gson;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.wavefront.agent.config.ReportableConfig;
import com.wavefront.agent.config.LogsIngestionConfig;
import com.wavefront.agent.logsharvesting.InteractiveLogsTester;
import com.wavefront.agent.preprocessor.AgentPreprocessorConfiguration;
import com.wavefront.agent.preprocessor.PointLineBlacklistRegexFilter;
import com.wavefront.agent.preprocessor.PointLineWhitelistRegexFilter;
import com.wavefront.api.AgentAPI;
import com.wavefront.api.agent.AgentConfiguration;
import com.wavefront.api.agent.Constants;
import com.wavefront.common.Clock;
import com.wavefront.common.TaggedMetricName;
import com.wavefront.metrics.ExpectedAgentMetric;
import com.wavefront.metrics.JsonMetricsGenerator;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;

import org.apache.commons.lang.StringUtils;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jboss.resteasy.client.jaxrs.ClientHttpEngine;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.engines.ApacheHttpClient4Engine;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocation;
import org.jboss.resteasy.plugins.interceptors.encoding.AcceptEncodingGZIPFilter;
import org.jboss.resteasy.plugins.interceptors.encoding.GZIPDecodingInterceptor;
import org.jboss.resteasy.plugins.interceptors.encoding.GZIPEncodingInterceptor;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.PasswordAuthentication;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.management.NotificationEmitter;
import javax.net.ssl.HttpsURLConnection;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.ProcessingException;

/**
 * Agent that runs remotely on a server collecting metrics.
 *
 * @author Clement Pang (clement@wavefront.com)
 */
public abstract class AbstractAgent {

  protected static final Logger logger = Logger.getLogger("agent");
  protected final Counter activeListeners = Metrics.newCounter(ExpectedAgentMetric.ACTIVE_LISTENERS.metricName);

  private static final Gson GSON = new Gson();
  private static final int GRAPHITE_LISTENING_PORT = 2878;

  private static final int OPENTSDB_LISTENING_PORT = 4242;

  private static final double MAX_RETRY_BACKOFF_BASE_SECONDS = 60.0;
  private static final int MAX_SPLIT_BATCH_SIZE = 50000; // same value as default pushFlushMaxPoints

  @Parameter(names = {"-f", "--file"}, description =
      "Proxy configuration file")
  private String pushConfigFile = null;

  @Parameter(names = {"-c", "--config"}, description =
      "Local configuration file to use (overrides using the server to obtain a config file)")
  private String configFile = null;

  @Parameter(names = {"-p", "--prefix"}, description =
      "Prefix to prepend to all push metrics before reporting.")
  protected String prefix = null;

  @Parameter(names = {"-t", "--token"}, description =
      "Token to auto-register agent with an account")
  private String token = null;

  @Parameter(names = {"--testLogs"}, description = "Run interactive session for crafting logsIngestionConfig.yaml")
  private boolean testLogs = false;

  @Parameter(names = {"-l", "--loglevel"}, description =
      "Log level for push data (NONE/SUMMARY/DETAILED); NONE is default")
  protected String pushLogLevel = "NONE";

  @Parameter(names = {"-v", "--validationlevel"}, description =
      "Validation level for push data (NO_VALIDATION/NUMERIC_ONLY/TEXT_ONLY/ALL); NO_VALIDATION is default")
  protected String pushValidationLevel = "NUMERIC_ONLY";

  @Parameter(names = {"-h", "--host"}, description = "Server URL")
  protected String server = "http://localhost:8080/api/";

  @Parameter(names = {"--buffer"}, description = "File to use for buffering failed transmissions to Wavefront servers" +
      ". Defaults to buffer.")
  private String bufferFile = "buffer";

  @Parameter(names = {"--retryThreads"}, description = "Number of threads retrying failed transmissions. Defaults to " +
      "the number of processors (min. 4). Buffer files are maxed out at 2G each so increasing the number of retry " +
      "threads effectively governs the maximum amount of space the agent will use to buffer points locally")
  protected Integer retryThreads = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors()));

  @Parameter(names = {"--flushThreads"}, description = "Number of threads that flush data to the server. Defaults to" +
      "the number of processors (min. 4). Setting this value too large will result in sending batches that are too " +
      "small to the server and wasting connections. This setting is per listening port.")
  protected Integer flushThreads = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors()));

  @Parameter(names = {"--purgeBuffer"}, description = "Whether to purge the retry buffer on start-up. Defaults to " +
      "false.")
  private boolean purgeBuffer = false;

  @Parameter(names = {"--pushFlushInterval"}, description = "Milliseconds between flushes to . Defaults to 1000 ms")
  protected AtomicInteger pushFlushInterval = new AtomicInteger(1000);
  protected int pushFlushIntervalInitialValue = 1000; // store initially configured value to revert to

  @Parameter(names = {"--pushFlushMaxPoints"}, description = "Maximum allowed points in a single push flush. Defaults" +
      " to 50,000")
  protected AtomicInteger pushFlushMaxPoints = new AtomicInteger(50000);
  protected int pushFlushMaxPointsInitialValue = 50000; // store initially configured value to revert to

  @Parameter(names = {"--pushRateLimit"}, description = "Limit the outgoing point rate at the proxy. Default: " +
      "do not throttle.")
  protected Integer pushRateLimit = -1;

  @Parameter(names = {"--pushMemoryBufferLimit"}, description = "Max number of points that can stay in memory buffers" +
      " before spooling to disk. Defaults to 16 * pushFlushMaxPoints, minimum size: pushFlushMaxPoints. Setting this " +
      " value lower than default reduces memory usage but will force the proxy to spool to disk more frequently if " +
      " you have points arriving at the proxy in short bursts")
  protected AtomicInteger pushMemoryBufferLimit = new AtomicInteger(16 * pushFlushMaxPoints.get());

  @Parameter(names = {"--pushBlockedSamples"}, description = "Max number of blocked samples to print to log. Defaults" +
      " to 0.")
  protected Integer pushBlockedSamples = 0;

  @Parameter(names = {"--pushListenerPorts"}, description = "Comma-separated list of ports to listen on. Defaults to " +
      "2878.")
  protected String pushListenerPorts = "" + GRAPHITE_LISTENING_PORT;

  @Parameter(names = {"--memGuardFlushThreshold"}, description = "If heap usage exceeds this threshold (in percent), " +
      "flush pending points to disk as an additional OoM protection measure. Set to 0 to disable. Default: 95")
  protected int memGuardFlushThreshold = 95;

  @Parameter(
      names = {"--histogramStateDirectory"},
      description = "Directory for persistent agent state, must be writable.")
  protected String histogramStateDirectory = "/var/tmp";

  @Parameter(
      names = {"--histogramAccumulatorResolveInterval"},
      description = "Interval to write-back accumulation changes to disk in millis.")
  protected Long histogramAccumulatorResolveInterval = 100L;

  @Parameter(
      names = {"--histogramMinutesListenerPorts"},
      description = "Comma-separated list of ports to listen on. Defaults to none.")
  protected String histogramMinsListenerPorts = "";

  @Parameter(
      names = {"--histogramMinuteAccumulators"},
      description = "Number of accumulators per minute port")
  protected Integer histogramMinuteAccumulators = Runtime.getRuntime().availableProcessors();

  @Parameter(
      names = {"--histogramMinuteFlushSecs"},
      description = "Number of seconds to keep a minute granularity accumulator open for new samples.")
  protected Integer histogramMinuteFlushSecs = 70;

  @Parameter(
      names = {"--histogramHoursListenerPorts"},
      description = "Comma-separated list of ports to listen on. Defaults to none.")
  protected String histogramHoursListenerPorts = "";

  @Parameter(
      names = {"--histogramHourAccumulators"},
      description = "Number of accumulators per hour port")
  protected Integer histogramHourAccumulators = Runtime.getRuntime().availableProcessors();

  @Parameter(
      names = {"--histogramHourFlushSecs"},
      description = "Number of seconds to keep an hour granularity accumulator open for new samples.")
  protected Integer histogramHourFlushSecs = 4200;

  @Parameter(
      names = {"--histogramDaysListenerPorts"},
      description = "Comma-separated list of ports to listen on. Defaults to none.")
  protected String histogramDaysListenerPorts = "";

  @Parameter(
      names = {"--histogramDayAccumulators"},
      description = "Number of accumulators per day port")
  protected Integer histogramDayAccumulators = Runtime.getRuntime().availableProcessors();

  @Parameter(
      names = {"--histogramDayFlushSecs"},
      description = "Number of seconds to keep a day granularity accumulator open for new samples.")
  protected Integer histogramDayFlushSecs = 18000;

  @Parameter(
      names = {"--histogramDistListenerPorts"},
      description = "Comma-separated list of ports to listen on. Defaults to none.")
  protected String histogramDistListenerPorts = "";

  @Parameter(
      names = {"--histogramDistAccumulators"},
      description = "Number of accumulators per distribution port")
  protected Integer histogramDistAccumulators = Runtime.getRuntime().availableProcessors();

  @Parameter(
      names = {"--histogramDistFlushSecs"},
      description = "Number of seconds to keep a new distribution bin open for new samples.")
  protected Integer histogramDistFlushSecs = 70;

  @Parameter(
      names = {"--histogramAccumulatorSize"},
      description = "Expected upper bound of concurrent accumulations, ~ #timeseries * #parallel reporting bins")
  protected Long histogramAccumulatorSize = 100000L;

  @Parameter(
      names = {"--avgHistogramKeyBytes"},
      description = "Average number of bytes in a [UTF-8] encoded histogram key. Generally corresponds to a metric, " +
          "source and tags concatenation.")
  protected Integer avgHistogramKeyBytes = 150;

  @Parameter(
      names = {"--avgHistogramDigestBytes"},
      description = "Average number of bytes in a encoded histogram.")
  protected Integer avgHistogramDigestBytes = 500;

  @Parameter(
      names = {"--persistMessages"},
      description = "Whether histogram samples or distributions should be persisted to disk")
  protected boolean persistMessages = true;

  @Parameter(names = {"--persistMessagesCompression"}, description = "Enable LZ4 compression for histogram samples " +
      "persisted to disk. (Default: true)")
  protected boolean persistMessagesCompression = true;

  @Parameter(
      names = {"--persistAccumulator"},
      description = "Whether the accumulator should persist to disk")
  protected boolean persistAccumulator = true;

  @Parameter(
      names = {"--histogramCompression"},
      description = "Controls allowable number of centroids per histogram. Must be in [20;1000]")
  protected Short histogramCompression = 100;

  @Parameter(names = {"--graphitePorts"}, description = "Comma-separated list of ports to listen on for graphite " +
      "data. Defaults to empty list.")
  protected String graphitePorts = "";

  @Parameter(names = {"--graphiteFormat"}, description = "Comma-separated list of metric segments to extract and " +
      "reassemble as the hostname (1-based).")
  protected String graphiteFormat = "";

  @Parameter(names = {"--graphiteDelimiters"}, description = "Concatenated delimiters that should be replaced in the " +
      "extracted hostname with dots. Defaults to underscores (_).")
  protected String graphiteDelimiters = "_";

  @Parameter(names = {"--graphiteFieldsToRemove"}, description = "Comma-separated list of metric segments to remove (1-based)")
  protected String graphiteFieldsToRemove;

  @Parameter(names = {"--httpJsonPorts"}, description = "Comma-separated list of ports to listen on for json metrics " +
      "data. Binds, by default, to none.")
  protected String httpJsonPorts = "";

  @Parameter(names = {"--writeHttpJsonPorts"}, description = "Comma-separated list of ports to listen on for json metrics from collectd write_http json format " +
      "data. Binds, by default, to none.")
  protected String writeHttpJsonPorts = "";

  @Parameter(names = {"--filebeatPort"}, description = "Port on which to listen for filebeat data.")
  protected Integer filebeatPort = 0;

  @Parameter(names = {"--rawLogsPort"}, description = "Port on which to listen for raw logs data.")
  protected Integer rawLogsPort = 0;

  @Parameter(names = {"--hostname"}, description = "Hostname for the agent. Defaults to FQDN of machine.")
  protected String hostname;

  @Parameter(names = {"--idFile"}, description = "File to read agent id from. Defaults to ~/.dshell/id")
  protected String idFile = null;

  @Parameter(names = {"--graphiteWhitelistRegex"}, description = "(DEPRECATED for whitelistRegex)", hidden = true)
  protected String graphiteWhitelistRegex;

  @Parameter(names = {"--graphiteBlacklistRegex"}, description = "(DEPRECATED for blacklistRegex)", hidden = true)
  protected String graphiteBlacklistRegex;

  @Parameter(names = {"--whitelistRegex"}, description = "Regex pattern (java.util.regex) that graphite input lines must match to be accepted")
  protected String whitelistRegex;

  @Parameter(names = {"--blacklistRegex"}, description = "Regex pattern (java.util.regex) that graphite input lines must NOT match to be accepted")
  protected String blacklistRegex;

  @Parameter(names = {"--opentsdbPorts"}, description = "Comma-separated list of ports to listen on for opentsdb data. " +
      "Binds, by default, to none.")
  protected String opentsdbPorts = "";

  @Parameter(names = {"--opentsdbWhitelistRegex"}, description = "Regex pattern (java.util.regex) that opentsdb input lines must match to be accepted")
  protected String opentsdbWhitelistRegex;

  @Parameter(names = {"--opentsdbBlacklistRegex"}, description = "Regex pattern (java.util.regex) that opentsdb input lines must NOT match to be accepted")
  protected String opentsdbBlacklistRegex;

  @Parameter(names = {"--picklePorts"}, description = "Comma-separated list of ports to listen on for pickle protocol " +
      "data. Defaults to none.")
  protected String picklePorts;

  @Parameter(names = {"--splitPushWhenRateLimited"}, description = "Whether to split the push batch size when the push is rejected by Wavefront due to rate limit.  Default false.")
  protected boolean splitPushWhenRateLimited = false;

  @Parameter(names = {"--retryBackoffBaseSeconds"}, description = "For exponential backoff when retry threads are throttled, the base (a in a^b) in seconds.  Default 2.0")
  protected AtomicDouble retryBackoffBaseSeconds = new AtomicDouble(2.0);
  protected double retryBackoffBaseSecondsInitialValue = 2.0d;

  @Parameter(names = {"--customSourceTags"}, description = "Comma separated list of point tag keys that should be treated as the source in Wavefront in the absence of a tag named source or host")
  protected String customSourceTagsProperty = "fqdn";

  @Parameter(names = {"--agentMetricsPointTags"}, description = "Additional point tags and their respective values to be included into internal agent's metrics (comma-separated list, ex: dc=west,env=prod)")
  protected String agentMetricsPointTags = null;

  @Parameter(names = {"--ephemeral"}, description = "If true, this agent is removed from Wavefront after 24 hours of inactivity.")
  protected boolean ephemeral = false;

  @Parameter(names = {"--disableRdnsLookup"}, description = "When receiving Wavefront-formatted data without source/host specified, use remote IP address as source instead of trying to resolve the DNS name. Default false.")
  protected boolean disableRdnsLookup = false;

  @Parameter(names = {"--javaNetConnection"}, description = "If true, use JRE's own http client when making connections instead of Apache HTTP Client")
  protected boolean javaNetConnection = false;

  @Parameter(names = {"--gzipCompression"}, description = "If true, enables gzip compression for traffic sent to Wavefront (Default: true)")
  protected boolean gzipCompression = true;

  @Parameter(names = {"--soLingerTime"}, description = "If provided, enables SO_LINGER with the specified linger time in seconds (default: SO_LINGER disabled)")
  protected Integer soLingerTime = -1;

  @Parameter(names = {"--proxyHost"}, description = "Proxy host for routing traffic through a http proxy")
  protected String proxyHost = null;

  @Parameter(names = {"--proxyPort"}, description = "Proxy port for routing traffic through a http proxy")
  protected Integer proxyPort = 0;

  @Parameter(names = {"--proxyUser"}, description = "If proxy authentication is necessary, this is the username that will be passed along")
  protected String proxyUser = null;

  @Parameter(names = {"--proxyPassword"}, description = "If proxy authentication is necessary, this is the password that will be passed along")
  protected String proxyPassword = null;

  @Parameter(names = {"--httpUserAgent"}, description = "Override User-Agent in request headers")
  protected String httpUserAgent = null;

  @Parameter(names = {"--httpConnectTimeout"}, description = "Connect timeout in milliseconds (default: 5000)")
  protected Integer httpConnectTimeout = 5000;

  @Parameter(names = {"--httpRequestTimeout"}, description = "Request timeout in milliseconds (default: 20000)")
  protected Integer httpRequestTimeout = 20000;

  @Parameter(names = {"--preprocessorConfigFile"}, description = "Optional YAML file with additional configuration options for filtering and pre-processing points")
  protected String preprocessorConfigFile = null;

  @Parameter(names = {"--dataBackfillCutoffHours"}, description = "The cut-off point for what is considered a valid timestamp for back-dated points. Default is 8760 (1 year)")
  protected Integer dataBackfillCutoffHours = 8760;

  @Parameter(names = {"--logsIngestionConfigFile"}, description = "Location of logs ingestions config yaml file.")
  protected String logsIngestionConfigFile = null;

  @Parameter(description = "Unparsed parameters")
  protected List<String> unparsed_params;

  protected QueuedAgentService agentAPI;
  protected ResourceBundle props;
  protected final AtomicLong bufferSpaceLeft = new AtomicLong();
  protected List<String> customSourceTags = new ArrayList<>();
  protected final List<PostPushDataTimedTask> managedTasks = new ArrayList<>();
  protected final List<ExecutorService> managedExecutors = new ArrayList<>();
  protected final List<Runnable> shutdownTasks = new ArrayList<>();
  protected final AgentPreprocessorConfiguration preprocessors = new AgentPreprocessorConfiguration();
  protected RecyclableRateLimiter pushRateLimiter = null;
  protected final MemoryPoolMXBean tenuredGenPool = getTenuredGenPool();
  protected JsonNode agentMetrics;
  protected long agentMetricsCaptureTs;
  protected boolean shuttingDown = false;

  /**
   * A random value assigned at proxy start-up, to be reported in hexadecimal form as a point tag with
   * ~agent.session.id metric to detect ~agent metrics collisions caused by duplicate proxy names
   */
  protected final int sessionId = (int)(Math.random() * Integer.MAX_VALUE);

  protected final boolean localAgent;
  protected final boolean pushAgent;

  /**
   * Executors for support tasks.
   */
  private final ScheduledExecutorService agentConfigurationExecutor = Executors.newScheduledThreadPool(2,
      new NamedThreadFactory("agent-configuration"));
  private final ScheduledExecutorService queuedAgentExecutor = Executors.newScheduledThreadPool(retryThreads + 1,
      new NamedThreadFactory("submitter-queue"));
  protected UUID agentId;
  private final Runnable updateConfiguration = () -> {
    boolean doShutDown = false;
    try {
      AgentConfiguration config = fetchConfig();
      if (config != null) {
        processConfiguration(config);
        doShutDown = config.getShutOffAgents();
      }
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Exception occurred during configuration update", e);
    } finally {
      if (doShutDown) {
        logger.warning("Shutting down: Server side flag indicating agent has to shut down.");
        shutdown();
        System.exit(1);
      }
    }
  };

  private final Runnable updateAgentMetrics = () -> {
    @Nullable Map<String, String> pointTags = null;
    try {
      // calculate disk space available for queueing
      long maxAvailableSpace = 0;
      try {
        File bufferDirectory = new File(bufferFile).getAbsoluteFile();
        while (bufferDirectory != null && bufferDirectory.getUsableSpace() == 0) {
          bufferDirectory = bufferDirectory.getParentFile();
        }
        for (int i = 0; i < retryThreads; i++) {
          File buffer = new File(bufferFile + "." + i);
          if (buffer.exists()) {
            maxAvailableSpace += Integer.MAX_VALUE - buffer.length(); // 2GB max file size minus size used
          }
        }
        if (bufferDirectory != null) {
          // lesser of: available disk space or available buffer space
          bufferSpaceLeft.set(Math.min(maxAvailableSpace, bufferDirectory.getUsableSpace()));
        }
      } catch (Throwable t) {
        logger.warning("cannot compute remaining space in buffer file partition: " + t);
      }

      if (agentMetricsPointTags != null) {
        pointTags = Splitter.on(",").withKeyValueSeparator("=").split(agentMetricsPointTags);
      }
      synchronized (agentConfigurationExecutor) {
        agentMetricsCaptureTs = System.currentTimeMillis();
        agentMetrics = JsonMetricsGenerator.generateJsonMetrics(Metrics.defaultRegistry(),
            true, true, true, pointTags);
      }
    } catch (Exception ex) {
      logger.log(Level.SEVERE, "Could not generate agent metrics", ex);
    }
  };

  public AbstractAgent() {
    this(false, false);
  }

  public AbstractAgent(boolean localAgent, boolean pushAgent) {
    this.pushAgent = pushAgent;
    this.localAgent = localAgent;
    this.hostname = getLocalHostName();
    Metrics.newGauge(ExpectedAgentMetric.BUFFER_BYTES_LEFT.metricName,
        new Gauge<Long>() {
          @Override
          public Long value() {
            return bufferSpaceLeft.get();
          }
        }
    );

    Metrics.newGauge(new TaggedMetricName("session", "id", "id", Integer.toHexString(sessionId)),
        new Gauge<Integer>() {
          @Override
          public Integer value() {
            return 1;
          }
        }
    );

  }

  protected abstract void startListeners();

  protected abstract void stopListeners();

  private void initPreprocessors() throws IOException {
    // convert blacklist/whitelist fields to filters for full backwards compatibility
    // blacklistRegex and whitelistRegex are applied to pushListenerPorts, graphitePorts and picklePorts
    if (whitelistRegex != null || blacklistRegex != null) {
      String allPorts = StringUtils.join(new String[]{
          pushListenerPorts == null ? "" : pushListenerPorts,
          graphitePorts == null ? "" : graphitePorts,
          picklePorts == null ? "" : picklePorts
      }, ",");
      Iterable<String> ports = Splitter.on(",").omitEmptyStrings().trimResults().split(allPorts);
      for (String strPort : ports) {
        if (blacklistRegex != null) {
          preprocessors.forPort(strPort).forPointLine().addFilter(
              new PointLineBlacklistRegexFilter(blacklistRegex,
                  Metrics.newCounter(new TaggedMetricName("validationRegex", "points-rejected", "port", strPort))
              ));
        }
        if (whitelistRegex != null) {
          preprocessors.forPort(strPort).forPointLine().addFilter(
              new PointLineWhitelistRegexFilter(whitelistRegex,
                  Metrics.newCounter(new TaggedMetricName("validationRegex", "points-rejected", "port", strPort))
              ));
        }
      }
    }

    // opentsdbBlacklistRegex and opentsdbWhitelistRegex are applied to opentsdbPorts only
    if (opentsdbPorts != null && (opentsdbWhitelistRegex != null || opentsdbBlacklistRegex != null)) {
      Iterable<String> ports = Splitter.on(",").omitEmptyStrings().trimResults().split(opentsdbPorts);
      for (String strPort : ports) {
        if (opentsdbBlacklistRegex != null) {
          preprocessors.forPort(strPort).forPointLine().addFilter(
              new PointLineBlacklistRegexFilter(opentsdbBlacklistRegex,
                  Metrics.newCounter(new TaggedMetricName("validationRegex", "points-rejected", "port", strPort))
              ));
        }
        if (opentsdbWhitelistRegex != null) {
          preprocessors.forPort(strPort).forPointLine().addFilter(
              new PointLineWhitelistRegexFilter(opentsdbWhitelistRegex,
                  Metrics.newCounter(new TaggedMetricName("validationRegex", "points-rejected", "port", strPort))
              ));
        }
      }
    }

    if (preprocessorConfigFile != null) {
      FileInputStream stream = new FileInputStream(preprocessorConfigFile);
      preprocessors.loadFromStream(stream);
      logger.info("Preprocessor configuration loaded from " + preprocessorConfigFile);
    }
  }

  // Returns null on any exception, and logs the exception.
  protected LogsIngestionConfig loadLogsIngestionConfig() {
    try {
      if (logsIngestionConfigFile == null) {
        return null;
      }
      ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
      return objectMapper.readValue(new File(logsIngestionConfigFile), LogsIngestionConfig.class);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Could not load logs ingestion config", e);
      return null;
    }
  }

  private void loadListenerConfigurationFile() throws IOException {
    // If they've specified a push configuration file, override the command line values
    if (pushConfigFile != null) {
      ReportableConfig config = new ReportableConfig(pushConfigFile);
      try {
        prefix = Strings.emptyToNull(config.getString("prefix", prefix));
        pushLogLevel = config.getString("pushLogLevel", pushLogLevel);
        pushValidationLevel = config.getString("pushValidationLevel", pushValidationLevel);
        token = config.getRawProperty("token", token); // don't track
        server = config.getRawProperty("server", server); // don't track
        hostname = config.getString("hostname", hostname);
        idFile = config.getString("idFile", idFile);
        pushRateLimit = config.getNumber("pushRateLimit", pushRateLimit).intValue();
        pushBlockedSamples = config.getNumber("pushBlockedSamples", pushBlockedSamples).intValue();
        pushListenerPorts = config.getString("pushListenerPorts", pushListenerPorts);
        memGuardFlushThreshold = config.getNumber("memGuardFlushThreshold", memGuardFlushThreshold).intValue();
        histogramStateDirectory = config.getString("histogramStateDirectory", histogramStateDirectory);
        histogramAccumulatorResolveInterval = config.getNumber("histogramAccumulatorResolveInterval",
            histogramAccumulatorResolveInterval).longValue();
        histogramMinsListenerPorts = config.getString("histogramMinsListenerPorts", histogramMinsListenerPorts);
        histogramMinuteAccumulators = config.getNumber("histogramMinuteAccumulators", histogramMinuteAccumulators).
            intValue();
        histogramMinuteFlushSecs = config.getNumber("histogramMinuteFlushSecs", histogramMinuteFlushSecs).intValue();
        histogramHoursListenerPorts = config.getString("histogramHoursListenerPorts", histogramHoursListenerPorts);
        histogramHourAccumulators = config.getNumber("histogramHourAccumulators", histogramHourAccumulators).intValue();
        histogramHourFlushSecs = config.getNumber("histogramHourFlushSecs", histogramHourFlushSecs).intValue();
        histogramDaysListenerPorts = config.getString("histogramDaysListenerPorts", histogramDaysListenerPorts);
        histogramDayAccumulators = config.getNumber("histogramDayAccumulators", histogramDayAccumulators).intValue();
        histogramDayFlushSecs = config.getNumber("histogramDayFlushSecs", histogramDayFlushSecs).intValue();
        histogramDistListenerPorts = config.getString("histogramDistListenerPorts", histogramDistListenerPorts);
        histogramDistAccumulators = config.getNumber("histogramDistAccumulators", histogramDistAccumulators).intValue();
        histogramDistFlushSecs = config.getNumber("histogramDistFlushSecs", histogramDistFlushSecs).intValue();
        histogramAccumulatorSize = config.getNumber("histogramAccumulatorSize", histogramAccumulatorSize).longValue();
        histogramCompression = config.getNumber("histogramCompression", histogramCompression).shortValue();
        avgHistogramKeyBytes = config.getNumber("avgHistogramKeyBytes", avgHistogramKeyBytes).intValue();

        // these defaults should work well in most cases
        avgHistogramDigestBytes = 32 + Math.round(histogramCompression * 10.5f);

        avgHistogramDigestBytes = config.getNumber("avgHistogramDigestBytes", avgHistogramDigestBytes).intValue();
        persistAccumulator = config.getBoolean("persistAccumulator", persistAccumulator);
        persistMessages = config.getBoolean("persistMessages", persistMessages);
        persistMessagesCompression = config.getBoolean("persistMessagesCompression",
            persistMessagesCompression);

        retryThreads = config.getNumber("retryThreads", retryThreads).intValue();
        flushThreads = config.getNumber("flushThreads", flushThreads).intValue();
        httpJsonPorts = config.getString("jsonListenerPorts", httpJsonPorts);
        writeHttpJsonPorts = config.getString("writeHttpJsonListenerPorts", writeHttpJsonPorts);
        graphitePorts = config.getString("graphitePorts", graphitePorts);
        graphiteFormat = config.getString("graphiteFormat", graphiteFormat);
        graphiteFieldsToRemove = config.getString("graphiteFieldsToRemove", graphiteFieldsToRemove);
        graphiteDelimiters = config.getString("graphiteDelimiters", graphiteDelimiters);
        graphiteWhitelistRegex = config.getString("graphiteWhitelistRegex", graphiteWhitelistRegex);
        graphiteBlacklistRegex = config.getString("graphiteBlacklistRegex", graphiteBlacklistRegex);
        whitelistRegex = config.getString("whitelistRegex", whitelistRegex);
        blacklistRegex = config.getString("blacklistRegex", blacklistRegex);
        opentsdbPorts = config.getString("opentsdbPorts", opentsdbPorts);
        opentsdbWhitelistRegex = config.getString("opentsdbWhitelistRegex", opentsdbWhitelistRegex);
        opentsdbBlacklistRegex = config.getString("opentsdbBlacklistRegex", opentsdbBlacklistRegex);
        proxyHost = config.getString("proxyHost", proxyHost);
        proxyPort = config.getNumber("proxyPort", proxyPort).intValue();
        proxyPassword = config.getString("proxyPassword", proxyPassword, s -> "<removed>");
        proxyUser = config.getString("proxyUser", proxyUser);
        httpUserAgent = config.getString("httpUserAgent", httpUserAgent);
        httpConnectTimeout = config.getNumber("httpConnectTimeout", httpConnectTimeout).intValue();
        httpRequestTimeout = config.getNumber("httpRequestTimeout", httpRequestTimeout).intValue();
        javaNetConnection = config.getBoolean("javaNetConnection", javaNetConnection);
        gzipCompression = config.getBoolean("gzipCompression", gzipCompression);
        soLingerTime = config.getNumber("soLingerTime", soLingerTime).intValue();
        splitPushWhenRateLimited = config.getBoolean("splitPushWhenRateLimited", splitPushWhenRateLimited);
        customSourceTagsProperty = config.getString("customSourceTags", customSourceTagsProperty);
        agentMetricsPointTags = config.getString("agentMetricsPointTags", agentMetricsPointTags);
        ephemeral = config.getBoolean("ephemeral", ephemeral);
        disableRdnsLookup = config.getBoolean("disableRdnsLookup", disableRdnsLookup);
        picklePorts = config.getString("picklePorts", picklePorts);
        bufferFile = config.getString("buffer", bufferFile);
        preprocessorConfigFile = config.getString("preprocessorConfigFile", preprocessorConfigFile);
        dataBackfillCutoffHours = config.getNumber("dataBackfillCutoffHours", dataBackfillCutoffHours).intValue();
        filebeatPort = config.getNumber("filebeatPort", filebeatPort).intValue();
        rawLogsPort = config.getNumber("rawLogsPort", rawLogsPort).intValue();
        logsIngestionConfigFile = config.getString("logsIngestionConfigFile", logsIngestionConfigFile);

        // track mutable settings
        pushFlushIntervalInitialValue = Integer.parseInt(config.getRawProperty("pushFlushInterval",
            String.valueOf(pushFlushInterval.get())).trim());
        pushFlushInterval.set(pushFlushIntervalInitialValue);
        config.reportSettingAsGauge(pushFlushInterval, "pushFlushInterval");

        pushFlushMaxPointsInitialValue = Integer.parseInt(config.getRawProperty("pushFlushMaxPoints",
            String.valueOf(pushFlushMaxPoints.get())).trim());
        // clamp values for pushFlushMaxPoints between 1..50000
        pushFlushMaxPointsInitialValue = Math.max(Math.min(pushFlushMaxPointsInitialValue, MAX_SPLIT_BATCH_SIZE), 1);
        pushFlushMaxPoints.set(pushFlushMaxPointsInitialValue);
        config.reportSettingAsGauge(pushFlushMaxPoints, "pushFlushMaxPoints");

        retryBackoffBaseSecondsInitialValue = Double.parseDouble(config.getRawProperty("retryBackoffBaseSeconds",
            String.valueOf(retryBackoffBaseSeconds.get())).trim());
        retryBackoffBaseSeconds.set(retryBackoffBaseSecondsInitialValue);
        config.reportSettingAsGauge(retryBackoffBaseSeconds, "retryBackoffBaseSeconds");

        /*
          default value for pushMemoryBufferLimit is 16 * pushFlushMaxPoints, but no more than 25% of available heap
          memory. 25% is chosen heuristically as a safe number for scenarios with limited system resources (4 CPU cores
          or less, heap size less than 4GB) to prevent OOM. this is a conservative estimate, budgeting 200 characters
          (400 bytes) per per point line. Also, it shouldn't be less than 1 batch size (pushFlushMaxPoints).
         */
        int listeningPorts = Iterables.size(Splitter.on(",").omitEmptyStrings().trimResults().split(pushListenerPorts));
        long calculatedMemoryBufferLimit = Math.max(Math.min(16 * pushFlushMaxPoints.get(),
            Runtime.getRuntime().maxMemory() / listeningPorts / 4 / flushThreads / 400), pushFlushMaxPoints.get());
        logger.fine("Calculated pushMemoryBufferLimit: " + calculatedMemoryBufferLimit);
        pushMemoryBufferLimit.set(Integer.parseInt(
            config.getRawProperty("pushMemoryBufferLimit", String.valueOf(pushMemoryBufferLimit.get())).trim()));
        config.reportSettingAsGauge(pushMemoryBufferLimit, "pushMemoryBufferLimit");
        logger.fine("Configured pushMemoryBufferLimit: " + pushMemoryBufferLimit);

        logger.warning("Loaded configuration file " + pushConfigFile);
      } catch (Throwable exception) {
        logger.severe("Could not load configuration file " + pushConfigFile);
        throw exception;
      }

      // Compatibility with deprecated fields
      if (whitelistRegex == null && graphiteWhitelistRegex != null) {
        whitelistRegex = graphiteWhitelistRegex;
      }

      if (blacklistRegex == null && graphiteBlacklistRegex != null) {
        blacklistRegex = graphiteBlacklistRegex;
      }

      initPreprocessors();

      if (!persistMessages) {
        persistMessagesCompression = false;
      }
      if (pushRateLimit > 0) {
        pushRateLimiter = RecyclableRateLimiter.create(pushRateLimit, 60);
      }

      pushMemoryBufferLimit.set(Math.max(pushMemoryBufferLimit.get(), pushFlushMaxPoints.get()));

      PostPushDataTimedTask.setPointsPerBatch(pushFlushMaxPoints);
      PostPushDataTimedTask.setMemoryBufferLimit(pushMemoryBufferLimit);
      QueuedAgentService.setSplitBatchSize(pushFlushMaxPoints);

      retryBackoffBaseSeconds.set(Math.max(
          Math.min(retryBackoffBaseSeconds.get(), MAX_RETRY_BACKOFF_BASE_SECONDS),
          1.0));
      QueuedAgentService.setRetryBackoffBaseSeconds(retryBackoffBaseSeconds);

      // for backwards compatibility - if pushLogLevel is defined in the config file, change log level programmatically
      Level level = null;
      switch (pushLogLevel) {
        case "NONE":
          level = Level.WARNING;
          break;
        case "SUMMARY":
          level = Level.INFO;
          break;
        case "DETAILED":
          level = Level.FINE;
          break;
      }
      if (level != null) {
        Logger.getLogger("agent").setLevel(level);
        Logger.getLogger(PostPushDataTimedTask.class.getCanonicalName()).setLevel(level);
        Logger.getLogger(QueuedAgentService.class.getCanonicalName()).setLevel(level);
      }
    }
  }

  /**
   * Entry-point for the application.
   *
   * @param args Command-line parameters passed on to JCommander to configure the daemon.
   */
  public void start(String[] args) throws IOException {
    try {
      // read build information and print version.
      props = ResourceBundle.getBundle("build");
      logger.info("Starting proxy version " + props.getString("build.version"));

      logger.info("Arguments: " + Joiner.on(", ").join(args));
      new JCommander(this, args);
      if (unparsed_params != null) {
        logger.info("Unparsed arguments: " + Joiner.on(", ").join(unparsed_params));
      }

      /* ------------------------------------------------------------------------------------
       * Configuration Setup.
       * ------------------------------------------------------------------------------------ */

      // 1. Load the listener configurations.
      loadListenerConfigurationFile();
      loadLogsIngestionConfig();

      managedExecutors.add(agentConfigurationExecutor);

      // Conditionally enter an interactive debugging session for logsIngestionConfig.yaml
      if (testLogs) {
        InteractiveLogsTester interactiveLogsTester = new InteractiveLogsTester(this::loadLogsIngestionConfig, prefix);
        logger.info("Reading line-by-line sample log messages from STDIN");
        while (interactiveLogsTester.interactiveTest()) {
          // empty
        }
        System.exit(0);
      }

      // 2. Read or create the unique Id for the daemon running on this machine.
      readOrCreateDaemonId();

      if (proxyHost != null) {
        System.setProperty("http.proxyHost", proxyHost);
        System.setProperty("https.proxyHost", proxyHost);
        System.setProperty("http.proxyPort", String.valueOf(proxyPort));
        System.setProperty("https.proxyPort", String.valueOf(proxyPort));
      }
      if (proxyUser != null && proxyPassword != null) {
        Authenticator.setDefault(
            new Authenticator() {
              @Override
              public PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() == RequestorType.PROXY) {
                  return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray());
                } else {
                  return null;
                }
              }
            }
        );
      }

      // create List of custom tags from the configuration string
      String[] tags = customSourceTagsProperty.split(",");
      for (String tag : tags) {
        tag = tag.trim();
        if (!customSourceTags.contains(tag)) {
          customSourceTags.add(tag);
        } else {
          logger.warning("Custom source tag: " + tag + " was repeated. Check the customSourceTags property in " +
              "wavefront.conf");
        }
      }

      // 3. Setup proxies.
      AgentAPI service = createAgentService();
      try {
        setupQueueing(service);
      } catch (IOException e) {
        logger.log(Level.SEVERE, "Cannot setup local file for queueing due to IO error", e);
        throw e;
      }

      // 4. Start the (push) listening endpoints
      startListeners();

      // set up OoM memory guard
      if (memGuardFlushThreshold > 0) {
        setupMemoryGuard((float)memGuardFlushThreshold / 100);
      }

      new Timer().schedule(
          new TimerTask() {
            @Override
            public void run() {
              try {
                // exit if no active listeners
                if (activeListeners.count() == 0) {
                  logger.severe("**** All listener threads failed to start - there is already a running instance " +
                      "listening on configured ports, or no listening ports configured!");
                  logger.severe("Aborting start-up");
                  System.exit(1);
                }

                // 5. Poll or read the configuration file to use.
                AgentConfiguration config;
                if (configFile != null) {
                  logger.info("Loading configuration file from: " + configFile);
                  try {
                    config = GSON.fromJson(new FileReader(configFile),
                        AgentConfiguration.class);
                  } catch (FileNotFoundException e) {
                    throw new RuntimeException("Cannot read config file: " + configFile);
                  }
                  try {
                    config.validate(localAgent);
                  } catch (RuntimeException ex) {
                    logger.log(Level.SEVERE, "cannot parse config file", ex);
                    throw new RuntimeException("cannot parse config file", ex);
                  }
                  agentId = null;
                } else {
                  updateAgentMetrics.run();
                  config = fetchConfig();
                  logger.info("scheduling regular configuration polls");
                  agentConfigurationExecutor.scheduleAtFixedRate(updateAgentMetrics, 10, 60, TimeUnit.SECONDS);
                  agentConfigurationExecutor.scheduleWithFixedDelay(updateConfiguration, 0, 1, TimeUnit.SECONDS);
                }
                // 6. Setup work units and targets based on the configuration.
                if (config != null) {
                  logger.info("initial configuration is available, setting up proxy agent");
                  processConfiguration(config);
                }

                Runtime.getRuntime().addShutdownHook(new Thread("proxy-shutdown-hook") {
                  @Override
                  public void run() {
                    shutdown();
                  }
                });

                logger.info("setup complete");
              } catch (Throwable t) {
                logger.log(Level.SEVERE, "Aborting start-up", t);
                System.exit(1);
              }
            }
          },
          5000
      );
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "Aborting start-up", t);
      System.exit(1);
    }
  }

  /**
   * Create RESTeasy proxies for remote calls via HTTP.
   */
  protected AgentAPI createAgentService() {
    ResteasyProviderFactory factory = ResteasyProviderFactory.getInstance();
    factory.registerProvider(JsonNodeWriter.class);
    if (!factory.getClasses().contains(ResteasyJackson2Provider.class)) {
      factory.registerProvider(ResteasyJackson2Provider.class);
    }
    if (httpUserAgent == null) {
      httpUserAgent = "Wavefront-Proxy/" + props.getString("build.version");
    }
    ClientHttpEngine httpEngine;
    if (javaNetConnection) {
      httpEngine = new JavaNetConnectionEngine() {
        @Override
        protected HttpURLConnection createConnection(ClientInvocation request) throws IOException {
          HttpURLConnection connection = (HttpURLConnection) request.getUri().toURL().openConnection();
          connection.setRequestProperty("User-Agent", httpUserAgent);
          connection.setRequestMethod(request.getMethod());
          connection.setConnectTimeout(httpConnectTimeout); // 5s
          connection.setReadTimeout(httpRequestTimeout); // 60s
          if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection secureConnection = (HttpsURLConnection) connection;
            secureConnection.setSSLSocketFactory(new SSLSocketFactoryImpl(
                HttpsURLConnection.getDefaultSSLSocketFactory(),
                httpRequestTimeout));
          }
          return connection;
        }
      };
    } else {
      HttpClient httpClient = HttpClientBuilder.create().
          useSystemProperties().
          disableAutomaticRetries().
          setUserAgent(httpUserAgent).
          setMaxConnTotal(200).
          setMaxConnPerRoute(100).
          setConnectionTimeToLive(1, TimeUnit.MINUTES).
          setDefaultSocketConfig(
              SocketConfig.custom().
                  setSoTimeout(httpRequestTimeout).build()).
          setSSLSocketFactory(new SSLConnectionSocketFactoryImpl(
              SSLConnectionSocketFactory.getSystemSocketFactory(),
              httpRequestTimeout)).
          // allow up to 1 retry for
              setRetryHandler(new DefaultHttpRequestRetryHandler(1, true)).
          setDefaultRequestConfig(
              RequestConfig.custom().
                  setContentCompressionEnabled(true).
                  setRedirectsEnabled(true).
                  setConnectTimeout(httpConnectTimeout).
                  setConnectionRequestTimeout(httpConnectTimeout).
                  setSocketTimeout(httpRequestTimeout).build()).
          build();
      final ApacheHttpClient4Engine apacheHttpClient4Engine = new ApacheHttpClient4Engine(httpClient, true);
      // avoid using disk at all
      apacheHttpClient4Engine.setFileUploadInMemoryThresholdLimit(100);
      apacheHttpClient4Engine.setFileUploadMemoryUnit(ApacheHttpClient4Engine.MemoryUnit.MB);
      httpEngine = apacheHttpClient4Engine;
    }
    ResteasyClient client;
    if (gzipCompression) {
      client = new ResteasyClientBuilder().
          httpEngine(httpEngine).
          providerFactory(factory).
          register(GZIPDecodingInterceptor.class).
          register(GZIPEncodingInterceptor.class).
          register(AcceptEncodingGZIPFilter.class).
          build();
    } else {
      client = new ResteasyClientBuilder().
          httpEngine(httpEngine).
          providerFactory(factory).
          build();
    }
    ResteasyWebTarget target = client.target(server);
    return target.proxy(AgentAPI.class);
  }

  private void setupQueueing(AgentAPI service) throws IOException {
    managedExecutors.add(queuedAgentExecutor);
    agentAPI = new QueuedAgentService(service, bufferFile, retryThreads, queuedAgentExecutor, purgeBuffer,
        agentId, splitPushWhenRateLimited, pushRateLimiter);
  }

  /**
   * Read or create the Daemon id for this machine. Reads from ~/.dshell/id.
   */
  private void readOrCreateDaemonId() {
    File agentIdFile;
    if (idFile != null) {
      agentIdFile = new File(idFile);
    } else {
      File userHome = new File(System.getProperty("user.home"));
      if (!userHome.exists() || !userHome.isDirectory()) {
        logger.severe("Cannot read from user.home, quitting");
        System.exit(1);
      }
      File configDirectory = new File(userHome, ".dshell");
      if (configDirectory.exists()) {
        if (!configDirectory.isDirectory()) {
          logger.severe(configDirectory + " must be a directory!");
          System.exit(1);
        }
      } else {
        if (!configDirectory.mkdir()) {
          logger.severe("Cannot create .dshell directory under " + userHome);
          System.exit(1);
        }
      }
      agentIdFile = new File(configDirectory, "id");
    }
    if (agentIdFile.exists()) {
      if (agentIdFile.isFile()) {
        try {
          agentId = UUID.fromString(Files.readFirstLine(agentIdFile, Charsets.UTF_8));
          logger.info("Proxy Agent Id read from file: " + agentId);
        } catch (IllegalArgumentException ex) {
          logger.severe("Cannot read proxy agent id from " + agentIdFile +
              ", content is malformed");
          System.exit(1);
        } catch (IOException e) {
          logger.log(Level.SEVERE, "Cannot read from " + agentIdFile, e);
          System.exit(1);
        }
      } else {
        logger.severe(agentIdFile + " is not a file!");
        System.exit(1);
      }
    } else {
      agentId = UUID.randomUUID();
      logger.info("Proxy Agent Id created: " + agentId);
      try {
        Files.write(agentId.toString(), agentIdFile, Charsets.UTF_8);
      } catch (IOException e) {
        logger.severe("Cannot write to " + agentIdFile);
        System.exit(1);
      }
    }
  }

  private void fetchConfigError(String errMsg, @Nullable String secondErrMsg) {
    logger.severe(Strings.repeat("*", errMsg.length()));
    logger.severe(errMsg);
    if (secondErrMsg != null) {
      logger.severe(secondErrMsg);
    }
    logger.severe(Strings.repeat("*", errMsg.length()));
  }

  /**
   * Fetch configuration of the daemon from remote server.
   *
   * @return Fetched configuration. {@code null} if the configuration is invalid.
   */
  @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
  private AgentConfiguration fetchConfig() {
    AgentConfiguration newConfig = null;
    JsonNode agentMetricsWorkingCopy;
    long agentMetricsCaptureTsWorkingCopy;
    synchronized(agentConfigurationExecutor) {
      if (agentMetrics == null) return null;
      agentMetricsWorkingCopy = agentMetrics;
      agentMetricsCaptureTsWorkingCopy = agentMetricsCaptureTs;
      agentMetrics = null;
    }
    logger.info("fetching configuration from server at: " + server);
    try {
      newConfig = agentAPI.checkin(agentId, hostname, token, props.getString("build.version"),
          agentMetricsCaptureTsWorkingCopy, localAgent, agentMetricsWorkingCopy, pushAgent, ephemeral);
      agentMetricsWorkingCopy = null;
    } catch (NotAuthorizedException ex) {
      fetchConfigError("HTTP 401 Unauthorized: Please verify that your server and token settings",
          "are correct and that the token has Agent Management permission!");
      return null;
    } catch (ClientErrorException ex) {
      if (ex.getResponse().getStatus() == 407) {
        fetchConfigError("HTTP 407 Proxy Authentication Required: Please verify that proxyUser and proxyPassword",
            "settings are correct and make sure your HTTP proxy is not rate limiting!");
        return null;
      }
      if (ex.getResponse().getStatus() == 404) {
        fetchConfigError("HTTP 404 Not Found: Please verify that your server setting is correct: " + server, null);
        return null;
      }
      fetchConfigError("HTTP " + ex.getResponse().getStatus() + " error: Unable to retrieve proxy agent configuration!",
          server + ": " + Throwables.getRootCause(ex).getMessage());
      return null;
    } catch (ProcessingException ex) {
      Throwable rootCause = Throwables.getRootCause(ex);
      if (rootCause instanceof UnknownHostException) {
        fetchConfigError("Unknown host: " + server + ". Please verify your DNS and network settings!", null);
        return null;
      }
      if (rootCause instanceof ConnectException ||
          rootCause instanceof SocketTimeoutException) {
        fetchConfigError("Unable to connect to " + server + ": " + rootCause.getMessage(),
            "Please verify your network/firewall settings!");
        return null;
      }
      fetchConfigError("Request processing error: Unable to retrieve proxy agent configuration!",
          server + ": " + rootCause);
      return null;
    } catch (Exception ex) {
      fetchConfigError("Unable to retrieve proxy agent configuration from remote server!",
          server + ": " + Throwables.getRootCause(ex));
      return null;
    } finally {
      synchronized(agentConfigurationExecutor) {
        // if check-in process failed (agentMetricsWorkingCopy is not null) and agent metrics have
        // not been updated yet, restore last known set of agent metrics to be retried
        if (agentMetricsWorkingCopy != null && agentMetrics == null) {
          agentMetrics = agentMetricsWorkingCopy;
        }
      }
    }
    try {
      if (newConfig.currentTime != null) {
        Clock.set(newConfig.currentTime);
      }
      newConfig.validate(localAgent);
    } catch (Exception ex) {
      logger.log(Level.WARNING, "configuration file read from server is invalid", ex);
      try {
        agentAPI.agentError(agentId, "Configuration file is invalid: " + ex.toString());
      } catch (Exception e) {
        logger.log(Level.WARNING, "cannot report error to collector", e);
      }
      return null;
    }
    return newConfig;
  }

  protected PostPushDataTimedTask[] getFlushTasks(String handle) {
    return getFlushTasks(Constants.PUSH_FORMAT_GRAPHITE_V2, handle);
  }

  protected PostPushDataTimedTask[] getFlushTasks(String pushFormat, String handle) {
    PostPushDataTimedTask[] toReturn = new PostPushDataTimedTask[flushThreads];
    logger.info("Using " + flushThreads + " flush threads to send batched " + pushFormat +
        " data to Wavefront for data received on port: " + handle);
    for (int i = 0; i < flushThreads; i++) {
      final PostPushDataTimedTask postPushDataTimedTask =
          new PostPushDataTimedTask(pushFormat, agentAPI, agentId, handle, i, pushRateLimiter, pushFlushInterval.get());
      toReturn[i] = postPushDataTimedTask;
      managedTasks.add(postPushDataTimedTask);
    }
    return toReturn;
  }

  /**
   * Actual agents can do additional configuration.
   *
   * @param config The configuration to process.
   */
  protected void processConfiguration(AgentConfiguration config) {
    try {
      agentAPI.agentConfigProcessed(agentId);
    } catch (RuntimeException e) {
      // cannot throw or else configuration update thread would die.
    }
  }

  public void shutdown() {
    if (shuttingDown) {
      return; // we need it only once
    }
    shuttingDown = true;
    try {
      try {
        logger.info("Shutting down: Stopping listeners...");
      } catch (Throwable t) {
        // ignore logging errors
      }

      stopListeners();

      try {
        logger.info("Shutting down: Stopping schedulers...");
      } catch (Throwable t) {
        // ignore logging errors
      }

      for (ExecutorService executor : managedExecutors) {
        executor.shutdownNow();
        executor.awaitTermination(1000L, TimeUnit.MILLISECONDS);
      }

      managedTasks.forEach(PostPushDataTimedTask::shutdown);

      try {
        logger.info("Shutting down: Flushing pending points...");
      } catch (Throwable t) {
        // ignore logging errors
      }

      for (PostPushDataTimedTask task : managedTasks) {
        while (task.getNumPointsToSend() > 0) {
          task.drainBuffersToQueue();
        }
      }
      try {
        logger.info("Shutting down: Running finalizing tasks...");
      } catch (Throwable t) {
        // ignore logging errors
      }

      shutdownTasks.forEach(Runnable::run);

      try {
        logger.info("Shutdown complete");
      } catch (Throwable t) {
        // ignore logging errors
      }
    } catch (Throwable t) {
      try {
        logger.log(Level.SEVERE, "Error during shutdown: ", t);
      } catch (Throwable loggingError) {
        t.addSuppressed(loggingError);
        t.printStackTrace();
      }
    }
  }

  private static String getLocalHostName() {
    InetAddress localAddress = null;
    try {
      Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
      while (nics.hasMoreElements()) {
        NetworkInterface network = nics.nextElement();
        if (!network.isUp() || network.isLoopback()) {
          continue;
        }
        for (Enumeration<InetAddress> addresses = network.getInetAddresses(); addresses.hasMoreElements(); ) {
          InetAddress address = addresses.nextElement();
          if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()) {
            continue;
          }
          if (address instanceof Inet4Address) { // prefer ipv4
            localAddress = address;
            break;
          }
          if (localAddress == null) {
            localAddress = address;
          }
        }
      }
    } catch (SocketException ex) {
      // ignore
    }
    if (localAddress != null) {
      return localAddress.getCanonicalHostName();
    }
    return "localhost";
  }

  private MemoryPoolMXBean getTenuredGenPool() {
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP && pool.isUsageThresholdSupported()) {
        return pool;
      }
    }
    return null;
  }

  private void setupMemoryGuard(double threshold) {
    if (tenuredGenPool == null) return;
    tenuredGenPool.setUsageThreshold((long) (tenuredGenPool.getUsage().getMax() * threshold));

    NotificationEmitter emitter = (NotificationEmitter) ManagementFactory.getMemoryMXBean();
    emitter.addNotificationListener((notification, obj) -> {
      if (notification.getType().equals(
          MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED)) {
        logger.warning("Heap usage threshold exceeded - draining buffers to disk!");
        for (PostPushDataTimedTask task : managedTasks) {
          if (task.getNumPointsToSend() > 0) {
            task.drainBuffersToQueue();
          }
        }
        logger.info("Draining buffers to disk: finished");
      }
    }, null, null);
  }
}
