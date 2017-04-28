#!groovy

/**
 * Stitch MIPs in memory
 * Write out tileConfig files for full 3D data
 * 
 * @author Jan Eglinger
 */

#@File stgFile
#@File ndFile
#@String(visibility="MESSAGE", value="Note that the nd file will be renamed to *.nd.tmp during processing, to avoid issues with bio-formats") message
#@boolean overrideCalibration
#@int imageWidth
#@int imageHeight
#@double pixelSizeXY
#@double pixelSizeZ
#@boolean autoGridSize
#@int rows
#@int cols
#@LogService log

// import static groovy.json.JsonOutput.* // for pretty printing

import com.opencsv.CSVParser
import ij.IJ
import ij.ImagePlus
import ij.plugin.RGBStackMerge
import ij.plugin.ZProjector
import java.nio.file.Files
import loci.plugins.BF
import loci.plugins.in.ImporterOptions
import mpicbg.stitching.CollectionStitchingImgLib
import mpicbg.stitching.fusion.Fusion
import mpicbg.stitching.ImageCollectionElement
import mpicbg.stitching.StitchingParameters
import mpicbg.models.TranslationModel2D
import net.imglib2.img.display.imagej.ImageJFunctions


/**
 * Parse a Metamorph .nd file using CSVParser
 * TODO factor out into common library
 * 
 * @param file - ND file object
 * 
 * @return positions
 * @return channels
 * @return dimensions
 * @return settings
 */
def parseND(file) {
	def positions = [:]
	def channels = [:]
	def dimensions = [:]
	def settings = [:]
	parser = new CSVParser(',' as char, '"' as char)
	file.eachLine {
		cols = parser.parseLine(it)
		switch (cols[0]) {
			// Stage positions
			case { it.startsWith("Stage")}:
				positions.put(cols[0][5..-1] as int, cols[1])
				break

			// Channel names and z config
			case { it.startsWith("WaveName")}:
				//channels.put(cols[0][8..-1] as int, cols[1])
				channels[cols[0][8..-1]] = channels[cols[0][8..-1]] ?: [:]
				channels[cols[0][8..-1]].put('name', cols[1])
				break
			case { it.startsWith("WaveDoZ")}:
				//channels.put(cols[0][8..-1] as int, cols[1])
				channels[cols[0][7..-1]] = channels[cols[0][7..-1]] ?: [:]
				channels[cols[0][7..-1]].put('stack', cols[1].trim() == "TRUE")
				break

			// Settings
			case "NDInfoFile":
				settings.put("Version", cols[1][8..-1].toDouble().round())
				break
			case "DoTimelapse":
				settings.put("DoTimelapse", cols[1].trim() == "TRUE")
				break
			case "DoStage":
				settings.put("DoStage", cols[1].trim() == "TRUE")
				break
			case "DoWave":
				settings.put("DoWave", cols[1].trim() == "TRUE")
				break
			case "DoZSeries":
				settings.put("DoZSeries", cols[1].trim() == "TRUE")
				break
			case "WaveInFileName":
				settings.put("WaveInFileName", cols[1].trim() == "TRUE")
				break

			// Dimensions
			case "NWavelengths":
				dimensions.put("c", cols[1] as int)
				break
			case "NZSteps":
				dimensions.put("z", cols[1] as int)
				break
			case "NTimePoints":
				dimensions.put("t", cols[1] as int)
				break
			case "NStagePositions":
				dimensions.put("s", cols[1] as int)
				break
			default:
				break
		}
	}
	[positions, channels, dimensions, settings]
}

/**
 * Parse a Metamorph .stg file using CSVParser
 * TODO factor out into common library
 * 
 * @param file - STG file object
 * @param scaleX - pixel width calibration
 * @param scaleY - pixel hieght calibration
 * 
 * @return coordinates
 */
def parseSTG(file, scaleX, scaleY) {
	def coords = [:]
	parser = new CSVParser(',' as char, '"' as char)
	file.eachLine(-3) { line, n -> // TODO do we need the actual value of n here?
		if (n > 0) {
			cols = parser.parseLine(line)
			xy = [x: (cols[1] as double) / scaleX, y: (cols[2] as double) / scaleY]
			coords[cols[0]] = xy
		}
	}
	coords
}

/**
 * Find patches of connected tiles in a list of coordinates
 */
def getPatches(coords, width, height) {
	patches = []
	coords.each { key, pos ->
		for (patch in patches) {
			if ((pos.x >= patch.xmin - width) &&
				(pos.x <= patch.xmax + width) &&
				(pos.y >= patch.ymin - height) &&
				(pos.y <= patch.ymax + height)) {
					patch.elements[key] = pos
					patch.xmin = Math.min(patch.xmin, pos.x)
					patch.xmax = Math.max(patch.xmax, pos.x)
					patch.ymin = Math.min(patch.ymin, pos.y)
					patch.ymax = Math.max(patch.ymax, pos.y)
					return // continue to next position
			}
		}
		patches << [elements: [(key):pos], xmin: pos.x, xmax: pos.x, ymin: pos.y, ymax: pos.y]
	}
	return patches
}

/**
 * Create file name string from metadata info
 */
def createFileString(info) {
	info.basename + (info.wave ?: "") + info.stage + (info.time ?: "") + info.ext
}

/**
 * Create a map mapping channel index and position name to file names
 */
def getFileMap(file, settings, channels, tiles) {
	// for each position and channel: generate single file name
	def fileInfo = [:]
	fileInfo.basename = ndFile.name.take(ndFile.name.lastIndexOf('.'))

	posToFileMap = [:]
	channelMap.each { cIndex, chEntry  ->
		fileInfo.wave = (settings.DoWave && settings.WaveInFileName) ? "_w$cIndex${chEntry.name}" : ""
		posToFileMap[cIndex] = [:]
		tileMap.each { stagePos, posName ->
			fileInfo.stage = "_s$stagePos"
			fileInfo.ext = (chEntry.stack && settings.Version == 1) ? ".stk" : ".TIF" // TODO capitalization?
			//println createFileString(fileInfo)
			//println posName
			posToFileMap[cIndex][posName] = createFileString(fileInfo)
		}
	}

	return posToFileMap
}

/**
 * Create a maximum intensity projection
 */
def getMIP(imp) {
	zp = new ZProjector(imp)
	zp.setMethod(ZProjector.MAX_METHOD)
	zp.setStopSlice(imp.getNSlices())
	zp.doHyperStackProjection(false)
	imp.close()
	return zp.getProjection()
}

/**
 * Write TileConfiguration_basename_channel.txt
 */
def writeTileConfig(ndFile, channelName, fileMap, coordMap, dim) {
	tcFile = new File(ndFile.getParent(), ndFile.name.take(ndFile.name.lastIndexOf('.')) + "_${channelName}_TileConfiguration.txt")
	tcFile.withWriter { writer ->
		writer.write("# Tile Configuration generated by Stitch_ND_File_With_STG_Info.groovy\n")
		writer.write("dim = $dim\n")
		writer.write("# Image Coordinates\n")
		thirdDim = (dim == 3) ? ", 0" : ""
		fileMap.each { key, name ->
			def line = "$name; ; (${coordMap[key].x} , ${coordMap[key].y}$thirdDim)"
			//def line = nd.getName() + "; " + (it[0]-1) + "; " + "( " + (it[1]/sizeX) + ", " + (it[2]/sizeY) + ", 0)"
			writer.write(line)
			writer.newLine()
		}
	}
	log.info("Finished writing " + tcFile.getName())
}

/**
 * Write TileConfiguration_basename_channel.txt
 */
def writeTileConfigPatch(ndFile, channelName, fileMap, coordMap, patch, dim) {
	tcFile = new File(ndFile.getParent(), ndFile.name.take(ndFile.name.lastIndexOf('.')) + "_${channelName}_TileConfiguration.txt")
	tcFile.withWriter { writer ->
		writer.write("# Tile Configuration generated by Stitch_ND_File_With_STG_Info.groovy\n")
		writer.write("dim = $dim\n")
		writer.write("# Image Coordinates\n")
		thirdDim = (dim == 3) ? ", 0" : ""
		patch.elements.each { pName, coords ->
			def line = "${fileMap[pName]}; ; (${coords.x} , ${coords.y}$thirdDim)"
			writer.write(line)
			writer.newLine()
		}
	}
	//log.info("Finished writing " + tcFile.getName())
}

/**
 * Verify the given closure and log any assertion error
 */
def verify(Closure closure) {
	try {
		closure()
		return true
	} catch (AssertionError e) {
		log.error(e.toString())
		return false
	}
}

def main() {
	// assert:
	//  * stg file present
	if (!verify { assert(stgFile.exists()) }) return
	//  * nd file present
	if (!verify { assert(ndFile.exists())  }) return
	//  * parent directory writeable
	if (!verify { assert(Files.isWritable(ndFile.getParentFile().toPath())) }) return

	// parse nd to get number of series
	(tileMap, channelMap, dimensions, settings) = parseND(ndFile)
	//println prettyPrint(toJson(tileMap))

	// parse stg to get coord map
	coordMap = parseSTG(stgFile, pixelSizeXY, pixelSizeXY)
	//println prettyPrint(toJson(coordMap))

	// get image and pixel size
	if (overrideCalibration) {
		tileHeight = imageHeight
		tileWidth = imageWidth
		pixelX = pixelSizeXY
		pixelY = pixelSizeXY
		// pixelX = 0.4580150
	} else {
		// parse dataset
		log.error("Automatic pixel size not yet implemented. Please supply pixel size and select overrideCalibration.")
		return
	}

	// get list of connected patches
	patchList = getPatches(coordMap, tileWidth, tileHeight)
	// TODO allow manual choice of grid size
	//println patchList.size
	//println prettyPrint(toJson(patchList))

	// get file name map (per channel, per patch)
	fileMap = getFileMap(ndFile, settings, channelMap, tileMap)
	//println prettyPrint(toJson(fileMap))

	inverseTileMap = tileMap.collectEntries { [it.value, it.key] }

	// rename nd file to mask it
	tmpFile = new File(ndFile.path + ".tmp")
	try {
		ndFile.renameTo(tmpFile)
		println "Renamed ${ndFile.name} to ${tmpFile.name}"

		// process each patch subsequently
		patchList.eachWithIndex { patch, patchIndex ->
			println "Starting new patch"
			tiles = []
			patch.elements.each { posName, coords ->
				imps = []
				fileMap.each { chIndex, fileNames -> //  take each channel
					current = fileNames[posName]
					println "Loading file $current"
					println (ndFile.getParent() + "/" + current)
					imps << IJ.openImage(new File(ndFile.getParent(), current).path)
				}
				merged = RGBStackMerge.mergeChannels((ImagePlus[]) imps, false)
				//merged.show()
				mip = getMIP(merged)
				//mip.show()
				println "Finished processing $posName."
				// add to new List<ImageCollectionElement> with coords
				ice = new ImageCollectionElement(ndFile, inverseTileMap[posName] as int)
				ice.setImagePlus(mip)
				ice.setModel( new TranslationModel2D() ) // always 2D for MIPs
				ice.setOffset( [ ( coords.x ), ( coords.y ) ] as float[] )
				tiles << ice
			}
			tiles.each {
				println (it.getIndex() + " " + it.getOffset())
			}

			// Stitch (taken mainly from Stitching_Grid.java)
			params = new StitchingParameters()
			params.dimensionality = 2
			params.cpuMemChoice = 1 // More RAM, less time FIXME offer choice?
			params.channel1 = 0
			params.channel2 = 0
			params.timeSelect = 0
			params.checkPeaks = 5
			params.regThreshold = 0.3
			params.computeOverlap = true
			params.displayFusion = false
			params.subpixelAccuracy = true
			params.fusionMethod = 0 // Linear Blending
			params.outputDirectory = null
			
			stitchedList = CollectionStitchingImgLib.stitchCollection(tiles, params)

			tiles.each {
				m = it.getModel()
				tmp = new double[ 2 ]
				m.applyInPlace( tmp )
				// Update coordMap at current index
				coordMap[tileMap[it.getIndex()]].x = tmp[0]
				coordMap[tileMap[it.getIndex()]].y = tmp[1]
			}

			//println prettyPrint(toJson(coordMap))

			// Write TileConfiguration files for full 3D stacks, for 1) each channel 2) multiseries file
			// write basename_channel_patch_mipRegisteredTileConfiguration.txt
			channelMap.each { cIndex, chEntry  ->
				channelName = "w${cIndex}${chEntry.name}_patch${patchIndex}"
				writeTileConfigPatch(ndFile, channelName, fileMap[cIndex], coordMap, patch, 3)
			}

			// TODO write multiseries TileConf

			// Fuse
			images = []
			models = []
			stitchedList.each {
				images << it.getImagePlus()
				models << it.getModel()
			}
			noOverlap = false
			type = ImageJFunctions.wrap(images[0]).firstElement()
			fused = Fusion.fuse( type, images, models, params.dimensionality, params.subpixelAccuracy, params.fusionMethod, params.outputDirectory, noOverlap, false, params.displayFusion )

			// Show/Save
			fused.show()

			// Close all ImagePlus objects
			images.each {
				it.close()
			}
		}

	} finally {
		tmpFile.renameTo(ndFile)
	}
}

main()
