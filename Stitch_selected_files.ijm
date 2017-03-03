// @File(label = "Input directory", style = "directory") inDir
// @File(label = "Output directory", style = "directory") outDir
// @boolean(label = "Process all files") processAll
// @boolean(label = "Quick stitching (do not compute overlap)") doQuick

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
		// save using Bio-Formats exporter
		close();
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
        if(File.isDirectory(input + list[i]))
            parseFolder("" + input + list[i]);
        if(endsWith(list[i], extension)) {
            folderList = Array.concat(folderList, input);
            fileList = Array.concat(fileList, list[i]);
        }
    }
}
