// @File(label = "Input directory", style = "directory") inDir
// @File(label = "Output directory", style = "directory") outDir
// @boolean(label = "Process all files") processAll
// @boolean(label = "Quick stitching (do not compute overlap)") doQuick
// @double(label = "Pixel size (in µm)", value=1.0) scaleXY

var extension = ".txt";
var fileList = newArray();
var folderList = newArray();
var selectedFiles = newArray();
var selectedFolders = newArray();

setBatchMode(true);
main();
setBatchMode(false);

function main() {
	parseFolder(inDir);
	if (processAll) {
		stitchList(folderList, fileList);
	} else {
		showSelectionDialog(folderList, fileList);
		stitchList(selectedFolders, selectedFiles);
	}
}

function stitchList(folders, files) {
	prefix = "type=[Positions from file] order=[Defined by TileConfiguration] directory=[";
	middle = "] layout_file=[";
	suffix = "] fusion_method=[Linear Blending]";
	if (!doQuick)
		suffix += " compute_overlap";
	suffix += " computation_parameters=[Save computation time (but use more RAM)] image_output=[Fuse and display]";
	for (i=0; i<files.length; i++) {
		optionString = prefix + folders[i] + middle + files[i] + suffix;
		//print (optionString);
		print ("Now stitching " + folders[i] + File.separator + files[i]);
		run("Grid/Collection stitching", optionString);
		// apply calibration
		run("Set Scale...", "distance=1 known=" + scaleXY + " unit=um");
		// save using Bio-Formats exporter
		if (isOpen("Fused")) {
			outPath = replace(folders[i], replace(inDir, "\\\\", "\\\\\\\\"), replace(outDir, "\\\\", "\\\\\\\\"));
			if (!File.exists(outPath)) {
				File.makeDirectory(outPath);
			}
			run("Bio-Formats Exporter", "save=[" + outPath + File.separator + files[i] + ".ids]");
			close();
		}
	}
}

function showSelectionDialog(folderList, fileList) {
	Dialog.create("Select files to process")
	for (i = 0; i < fileList.length; i++) {
		Dialog.addCheckbox(folderList[i] + File.separator + fileList[i], false);
	}
	Dialog.show();
	
	for (i = 0; i < fileList.length; i++) {
		if (Dialog.getCheckbox()) {
			selectedFiles = Array.concat(selectedFiles, fileList[i]);
			selectedFolders = Array.concat(selectedFolders, folderList[i]);
		}
	}	
}

function parseFolder(input) {
    list = getFileList(input);
    for (i = 0; i < list.length; i++) {
        if(File.isDirectory(input + File.separator + list[i]))
            parseFolder("" + input + File.separator + list[i]);
        if(endsWith(list[i], extension)) {
            folderList = Array.concat(folderList, input);
            fileList = Array.concat(fileList, list[i]);
        }
    }
}
