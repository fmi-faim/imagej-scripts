// @File ndFile
// @File stgFile
// @boolean overrideCalibration
// @double pixelSizeXY
// @double pixelSizeZ
// @String(choices={"2D", "3D"}) dimensionality
// @FormatService fs
// @LogService log

import groovy.time.TimeCategory
import loci.formats.ImageReader
import loci.formats.MetadataTools
import com.opencsv.CSVParser

def main() {
	// timer { bioformatsParse() }
	// timer { scifioParse() }

	/* Parse nd file */
	(tileMap, channelMap, dimensions, settings) = parseND(ndFile)
	
	log.info(tileMap)
	log.info(channelMap)
	log.info(dimensions)
	log.info(settings)


	basename = "Test"
	def fileInfo = [:]
	fileInfo.basename = ndFile.name.take(ndFile.name.lastIndexOf('.'))

	// TODO for each position: generate multiSeries name

	// for each position and channel: generate single file name
	posToFileMap = [:]
	channelMap.each { cIndex, chEntry  ->
		fileInfo.wave = (settings.DoWave && settings.WaveInFileName) ? "_w$cIndex${chEntry.name}" : ""
		posToFileMap[cIndex] = [:]
		tileMap.each { stagePos, posName ->
			fileInfo.stage = "_s$stagePos"
			fileInfo.ext = (chEntry.stack && settings.Version == 1) ? ".stk" : ".tif"
			//println createFileString(fileInfo)
			//println posName
			posToFileMap[cIndex][posName] = createFileString(fileInfo)
		}
	}

	log.info(posToFileMap)

	//   if !overrideCalibration:
	//     use BF or SCIFIO to get calibration data and dimensionality
	//   create map: Position label -> Series number, Single file

	// only if mode=positions
	// parse stg file
	//   create map: Position label -> coordinates / pixelSizeXY

	// required: map: dataset source => x,y,(z) coordinates


	// write TileConfig files
	//  - multiSeries: use ndFile and positions
	//  - manual: use single files, respect channel if necessary

}

timer = { closure ->
	log.info("Timer started")
	timeStart = new Date()
    closure.call()
	timeStop = new Date()
	duration = TimeCategory.minus(timeStop, timeStart)
	log.info("Time spent: " + duration)
}

// TODO get list of series 1) manually 2) using BF 3) using SCIFIO
def bioformatsParse() {
	ndPath = ndFile.getPath()
	reader = new ImageReader()
	omeMeta = MetadataTools.createOMEXMLMetadata()
	reader.setMetadataStore(omeMeta)
	reader.setId(ndPath)
	def dims = [] // TODO also get total size Z, return map
	dims << omeMeta.getPixelsPhysicalSizeX(0)
	dims << omeMeta.getPixelsPhysicalSizeY(0)
	reader.close()
	log.info(dims)
}

def scifioParse() {
	ndPath = ndFile.getPath()
	println ("Parsing with SCIFIO: " + ndPath)
	def format = fs.getFormat(ndPath)
	def metadata = format.createParser().parse(ndPath).get(0)
	def dims = [] // TODO also get total size Z, return map
	metadata.getAxes().each { axis ->
		dims << axis.averageScale(0,1)
	}
	log.info(dims)
}

def createFileString(info) {
	info.basename + (info.wave ?: "") + info.stage + (info.time ?: "") + info.ext
}

/**
 * Parse a Metamorph .nd file using CSVParser
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
		println cols
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
			default:
				break
		}
	}
	[positions, channels, dimensions, settings]
}

def parseSTG(file) {
	//
}

main()
