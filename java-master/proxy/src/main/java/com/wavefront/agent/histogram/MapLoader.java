package com.wavefront.agent.histogram;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import net.openhft.chronicle.hash.serialization.BytesReader;
import net.openhft.chronicle.hash.serialization.BytesWriter;
import net.openhft.chronicle.hash.serialization.SizedReader;
import net.openhft.chronicle.hash.serialization.SizedWriter;
import net.openhft.chronicle.map.ChronicleMap;
import net.openhft.chronicle.map.VanillaChronicleMap;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@link ChronicleMap}. If a file already exists at the given location, will make an attempt to load the map
 * from the existing file. Will fall-back to an in memory representation if the file cannot be loaded (see logs).
 *
 * @author Tim Schmidt (tim@wavefront.com).
 */
public class MapLoader<K, V, KM extends BytesReader<K> & BytesWriter<K>, VM extends SizedReader<V> & SizedWriter<V>> {
  private static final Logger logger = Logger.getLogger(MapLoader.class.getCanonicalName());

  private final Class<K> keyClass;
  private final Class<V> valueClass;
  private final long entries;
  private final double avgKeySize;
  private final double avgValueSize;
  private final KM keyMarshaller;
  private final VM valueMarshaller;
  private final boolean doPersist;
  private final LoadingCache<File, ChronicleMap<K, V>> maps =
      CacheBuilder.newBuilder().build(new CacheLoader<File, ChronicleMap<K, V>>() {

        private ChronicleMap<K, V> newPersistedMap(File file) throws IOException {
          return ChronicleMap.of(keyClass, valueClass)
              .keyMarshaller(keyMarshaller)
              .valueMarshaller(valueMarshaller)
              .entries(entries)
              .averageKeySize(avgKeySize)
              .averageValueSize(avgValueSize)
              .createPersistedTo(file);
        }

        private ChronicleMap<K, V> newInMemoryMap() {
          return ChronicleMap.of(keyClass, valueClass)
              .keyMarshaller(keyMarshaller)
              .valueMarshaller(valueMarshaller)
              .entries(entries)
              .averageKeySize(avgKeySize)
              .averageValueSize(avgValueSize)
              .create();
        }

        @Override
        public ChronicleMap<K, V> load(File file) throws Exception {
          if (!doPersist) {
            logger.log(
                Level.WARNING,
                "Accumulator persistence is disabled, unflushed histograms will be lost on agent shutdown."
            );
            return newInMemoryMap();
          }

          try {
            if (file.exists()) {
              logger.fine("Restoring accumulator state...");
              // Note: this relies on an uncorrupted header, which according to the docs would be due to a hardware error or fs bug.
              ChronicleMap<K, V> result = ChronicleMap
                  .of(keyClass, valueClass)
                  .entries(entries)
                  .averageKeySize(avgKeySize)
                  .averageValueSize(avgValueSize)
                  .recoverPersistedTo(file, false);

              if (result.isEmpty()) {
                // Create a new map with the supplied settings to be safe.
                result.close();
                file.delete();
                logger.fine("Accumulator map initialized");
                result = newPersistedMap(file);
              } else {
                // Note: as of 3.10 all instances are.
                if (result instanceof VanillaChronicleMap) {
                  logger.fine("Accumulator map restored");
                  VanillaChronicleMap vcm = (VanillaChronicleMap) result;
                  if (!vcm.keyClass().equals(keyClass) ||
                      !vcm.valueClass().equals(valueClass)) {
                    throw new IllegalStateException("Persisted map params are not matching expected map params "
                        + " key " + "exp: " + keyClass.getSimpleName() + " act: " + vcm.keyClass().getSimpleName()
                        + " val " + "exp: " + valueClass.getSimpleName() + " act: " + vcm.valueClass().getSimpleName());
                  }
                }
              }
              return result;

            } else {
              logger.info("Accumulator map initialized");
              return newPersistedMap(file);
            }
          } catch (Exception e) {
            logger.log(
                Level.SEVERE,
                "Failed to load/create map from '" + file.getAbsolutePath() +
                    "'. Please move or delete the file and restart the agent! Reason: ",
                e);
            System.exit(-1);
            return null;
          }
        }
      });

  /**
   * Creates a new {@link MapLoader}
   *
   * @param keyClass the Key class
   * @param valueClass the Value class
   * @param entries the maximum number of entries
   * @param avgKeySize the average marshaled key size in bytes
   * @param avgValueSize the average marshaled value size in bytes
   * @param keyMarshaller the key codec
   * @param valueMarshaller the value codec
   * @param doPersist whether to persist the map
   */
  public MapLoader(Class<K> keyClass,
                   Class<V> valueClass,
                   long entries,
                   double avgKeySize,
                   double avgValueSize,
                   KM keyMarshaller,
                   VM valueMarshaller,
                   boolean doPersist) {
    this.keyClass = keyClass;
    this.valueClass = valueClass;
    this.entries = entries;
    this.avgKeySize = avgKeySize;
    this.avgValueSize = avgValueSize;
    this.keyMarshaller = keyMarshaller;
    this.valueMarshaller = valueMarshaller;
    this.doPersist = doPersist;
  }

  public ChronicleMap<K, V> get(File f) {
    Preconditions.checkNotNull(f);
    try {
      return maps.get(f);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Failed loading map for " + f, e);
      return null;
    }
  }

  @Override
  public String toString() {
    return "MapLoader{" +
        "keyClass=" + keyClass +
        ", valueClass=" + valueClass +
        ", entries=" + entries +
        ", avgKeySize=" + avgKeySize +
        ", avgValueSize=" + avgValueSize +
        ", keyMarshaller=" + keyMarshaller +
        ", valueMarshaller=" + valueMarshaller +
        ", doPersist=" + doPersist +
        ", maps=" + maps +
        '}';
  }
}
