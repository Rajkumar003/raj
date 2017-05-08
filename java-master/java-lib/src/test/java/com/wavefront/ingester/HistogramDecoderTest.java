package com.wavefront.ingester;

import org.apache.commons.lang.time.DateUtils;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import sunnylabs.report.Histogram;
import sunnylabs.report.ReportPoint;

import static com.google.common.truth.Truth.assertThat;

/**
 * @author Tim Schmidt (tim@wavefront.com).
 */
public class HistogramDecoderTest {

  @Test
  public void testBasicMessage() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("!M 1471988653 #3 123.237 TestMetric source=Test key=value", out, "customer");

    assertThat(out).isNotEmpty();
    ReportPoint p = out.get(0);
    assertThat(p.getMetric()).isEqualTo("TestMetric");
    // Should be converted to Millis and pinned to the beginning of the corresponding minute
    assertThat(p.getTimestamp()).isEqualTo(1471988640000L);
    assertThat(p.getValue()).isNotNull();
    assertThat(p.getValue().getClass()).isEqualTo(Histogram.class);

    assertThat(p.getHost()).isEqualTo("Test");
    assertThat(p.getTable()).isEqualTo("customer");
    assertThat(p.getAnnotations()).isNotNull();
    assertThat(p.getAnnotations()).containsEntry("key", "value");

    Histogram h = (Histogram) p.getValue();

    assertThat(h.getDuration()).isEqualTo(DateUtils.MILLIS_PER_MINUTE);
    assertThat(h.getBins()).isNotNull();
    assertThat(h.getBins()).isNotEmpty();
    assertThat(h.getBins()).containsExactly(123.237D);
    assertThat(h.getCounts()).isNotNull();
    assertThat(h.getCounts()).isNotEmpty();
    assertThat(h.getCounts()).containsExactly(3);
  }


  @Test
  public void testHourBin() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("!H 1471988653 #3 123.237 TestMetric source=Test key=value", out, "customer");

    assertThat(out).isNotEmpty();
    ReportPoint p = out.get(0);
    // Should be converted to Millis and pinned to the beginning of the corresponding hour
    assertThat(p.getTimestamp()).isEqualTo(1471986000000L);
    assertThat(p.getValue()).isNotNull();
    assertThat(p.getValue().getClass()).isEqualTo(Histogram.class);

    Histogram h = (Histogram) p.getValue();

    assertThat(h.getDuration()).isEqualTo(DateUtils.MILLIS_PER_HOUR);
  }

  @Test
  public void testDayBin() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("!D 1471988653 #3 123.237 TestMetric source=Test key=value", out, "customer");

    assertThat(out).isNotEmpty();
    ReportPoint p = out.get(0);
    // Should be converted to Millis and pinned to the beginning of the corresponding day
    assertThat(p.getTimestamp()).isEqualTo(1471910400000L);
    assertThat(p.getValue()).isNotNull();
    assertThat(p.getValue().getClass()).isEqualTo(Histogram.class);

    Histogram h = (Histogram) p.getValue();

    assertThat(h.getDuration()).isEqualTo(DateUtils.MILLIS_PER_DAY);
  }

  @Test
  public void testTagKey() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("!M 1471988653 #3 123.237 TestMetric source=Test tag=value", out, "customer");

    assertThat(out).isNotEmpty();
    ReportPoint p = out.get(0);

    assertThat(p.getAnnotations()).isNotNull();
    assertThat(p.getAnnotations()).containsEntry("_tag", "value");
  }

  @Test
  public void testMultipleBuckets() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("!M 1471988653 #1 3.1416 #1 2.7183 TestMetric", out, "customer");

    assertThat(out).isNotEmpty();
    ReportPoint p = out.get(0);
    assertThat(p.getValue()).isNotNull();
    assertThat(p.getValue().getClass()).isEqualTo(Histogram.class);

    Histogram h = (Histogram) p.getValue();

    assertThat(h.getDuration()).isEqualTo(DateUtils.MILLIS_PER_MINUTE);
    assertThat(h.getBins()).isNotNull();
    assertThat(h.getBins()).isNotEmpty();
    assertThat(h.getBins()).containsExactly(3.1416D, 2.7183D);
    assertThat(h.getCounts()).isNotNull();
    assertThat(h.getCounts()).isNotEmpty();
    assertThat(h.getCounts()).containsExactly(1, 1);
  }

  @Test
  public void testNegativeMean() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("!M 1471988653 #1 -3.1416 TestMetric", out, "customer");

    assertThat(out).isNotEmpty();
    ReportPoint p = out.get(0);
    assertThat(p.getValue()).isNotNull();
    assertThat(p.getValue().getClass()).isEqualTo(Histogram.class);

    Histogram h = (Histogram) p.getValue();

    assertThat(h.getDuration()).isEqualTo(DateUtils.MILLIS_PER_MINUTE);
    assertThat(h.getBins()).isNotNull();
    assertThat(h.getBins()).isNotEmpty();
    assertThat(h.getBins()).containsExactly(-3.1416D);
    assertThat(h.getCounts()).isNotNull();
    assertThat(h.getCounts()).isNotEmpty();
    assertThat(h.getCounts()).containsExactly(1);
  }

  @Test(expected = RuntimeException.class)
  public void testMissingBin() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("1471988653 #3 123.237 TestMetric source=Test tag=value", out, "customer");
  }

  @Test(expected = RuntimeException.class)
  public void testMissingTimestamp() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("!M #3 123.237 TestMetric source=Test tag=value", out, "customer");
  }

  @Test(expected = RuntimeException.class)
  public void testMissingCentroids() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("!M 1471988653 TestMetric source=Test tag=value", out, "customer");
  }

  @Test(expected = RuntimeException.class)
  public void testMissingMean() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("!M #3 1471988653 TestMetric source=Test tag=value", out, "customer");
  }

  @Test(expected = RuntimeException.class)
  public void testMissingCount() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("!M 3.412 1471988653 TestMetric source=Test tag=value", out, "customer");
  }

  @Test(expected = RuntimeException.class)
  public void testZeroCount() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("!M #0 3.412 1471988653 TestMetric source=Test tag=value", out, "customer");
  }

  @Test(expected = RuntimeException.class)
  public void testMissingMetric() {
    HistogramDecoder decoder = new HistogramDecoder();
    List<ReportPoint> out = new ArrayList<>();

    decoder.decodeReportPoints("1471988653 #3 123.237 source=Test tag=value", out, "customer");
  }
}