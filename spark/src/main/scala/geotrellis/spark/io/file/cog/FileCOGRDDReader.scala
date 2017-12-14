/*
 * Copyright 2016 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.spark.io.file.cog

import java.io.File
import java.net.URI

import com.typesafe.config.ConfigFactory
import geotrellis.raster._
import geotrellis.raster.io.geotiff.GeoTiff
import geotrellis.raster.merge._
import geotrellis.raster.prototype._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.index.{IndexRanges, MergeQueue}
import geotrellis.spark.tiling.LayoutDefinition
import geotrellis.util._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.reflect._

/**
  * Generate VRTs to use from GDAL
  * */
trait FileCOGRDDReader[V <: CellGrid] extends Serializable {
  implicit val tileMergeMethods: V => TileMergeMethods[V]
  implicit val tilePrototypeMethods: V => TilePrototypeMethods[V]

  def readTiff(uri: URI, index: Int): GeoTiff[V]

  def getSegmentGridBounds(uri: URI, index: Int): (Int, Int) => GridBounds

  def tileTiff[K](tiff: GeoTiff[V], gridBounds: Map[GridBounds, K]): Vector[(K, V)]

  def read[K: SpatialComponent: Boundable: ClassTag](
    keyPath: BigInt => String,
    baseQueryKeyBounds: Seq[KeyBounds[K]],
    realQueryKeyBounds: Seq[KeyBounds[K]],
    decomposeBounds: KeyBounds[K] => Seq[(BigInt, BigInt)],
    sourceLayout: LayoutDefinition,
    overviewIndex: Int,
    numPartitions: Option[Int] = None,
    threads: Int = ConfigFactory.load().getThreads("geotrellis.file.threads.rdd.read")
  )(implicit sc: SparkContext): RDD[(K, V)] = {
    if (baseQueryKeyBounds.isEmpty) return sc.emptyRDD[(K, V)]

    val ranges = if (baseQueryKeyBounds.length > 1)
      MergeQueue(baseQueryKeyBounds.flatMap(decomposeBounds))
    else
      baseQueryKeyBounds.flatMap(decomposeBounds)

    val bins = IndexRanges.bin(ranges, numPartitions.getOrElse(sc.defaultParallelism))

    val realQueryKeyBoundsRange: Vector[K] =
      realQueryKeyBounds
        .flatMap { case KeyBounds(minKey, maxKey) =>
          val SpatialKey(minCol, minRow) = minKey.getComponent[SpatialKey]
          val SpatialKey(maxCol, maxRow) = maxKey.getComponent[SpatialKey]

          for {
            c <- minCol to maxCol
            r <- minRow to maxRow
          } yield minKey.setComponent[SpatialKey](SpatialKey(c, r))
        }
        .toVector

    sc.parallelize(bins, bins.size)
      .mapPartitions { partition: Iterator[Seq[(BigInt, BigInt)]] =>
        partition flatMap { seq =>
          LayerReader.njoin[K, V](seq.toIterator, threads) { index: BigInt =>
            val uri = new URI(s"file:///${keyPath(index)}.tiff")
            val tiff = readTiff(uri, overviewIndex)
            val rgb = sourceLayout.mapTransform(tiff.extent)

            val gb = tiff.rasterExtent.gridBounds
            val getGridBounds = getSegmentGridBounds(uri, overviewIndex)

            val map: Map[GridBounds, K] =
              realQueryKeyBoundsRange.flatMap { key =>
                val spatialKey = key.getComponent[SpatialKey]
                val minCol = (spatialKey.col - rgb.colMin) * sourceLayout.tileLayout.tileCols
                val minRow = (spatialKey.row - rgb.rowMin) * sourceLayout.tileLayout.tileRows

                if (minCol >= 0 && minRow >= 0 && minCol < tiff.cols && minRow < tiff.rows) {
                  val currentGb = getGridBounds(minCol, minRow)
                  gb.intersection(currentGb).map(gb => gb -> key)
                } else None
              }.toMap

            tileTiff(tiff, map)
          }
        }
      }
      .groupBy(_._1)
      .map { case (key, (seq: Iterable[(K, V)])) => key -> seq.map(_._2).reduce(_ merge _) }
  }
}


object FileCOGRDDReader {
  /** TODO: Think about it in a more generic fasion */
  private final val FileCOGRDDReaderRegistry: Map[String, FileCOGRDDReader[_]] =
    Map(
      classTag[Tile].runtimeClass.getCanonicalName -> implicitly[FileCOGRDDReader[Tile]],
      classTag[MultibandTile].runtimeClass.getCanonicalName -> implicitly[FileCOGRDDReader[MultibandTile]]
    )

  private [geotrellis] def fromRegistry[V <: CellGrid: ClassTag]: FileCOGRDDReader[V] =
    FileCOGRDDReaderRegistry
      .getOrElse(
        classTag[V].runtimeClass.getCanonicalName,
        throw new Exception(s"No FileCOGRDDReaderRegistry for the type ${classTag[V].runtimeClass.getCanonicalName}")
      ).asInstanceOf[FileCOGRDDReader[V]]

  def getReaders(uri: URI): (ByteReader, Option[ByteReader]) = {
    val path = uri.getPath
    val ovrPath = s"$path.ovr"
    val ovrPathExists = new File(ovrPath).isFile

    val ovrReader: Option[ByteReader] =
      if (ovrPathExists) Some(Filesystem.toMappedByteBuffer(ovrPath)) else None

    val reader: ByteReader = Filesystem.toMappedByteBuffer(ovrPath)

    reader -> ovrReader
  }
}

