package com.checkSheet.helper;

import com.checkSheet.config.FileStorageProperties;
import com.checkSheet.exception.CustomException;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

@Service
public class FileStorageUtil {
	@Value("${app.protocol:http}")
	private String protocolProperty = "http";  // Temporary non-static variable for injection

	private static String protocol;   // Static variable

	@PostConstruct
	public void init() {
		protocol = protocolProperty;  // Assign injected value to static variable
	}

	private static Path fileStorageLocation;
	
	private static FileStorageProperties fileStorageProperties;

	@Autowired
	public void setFileStorageProperties(FileStorageProperties fileStorageProperties) {
		FileStorageUtil.fileStorageProperties = fileStorageProperties;
	}


	public static void setPath(String path) throws CustomException {
		fileStorageLocation = Paths.get(fileStorageProperties.getUploadDir() + path).toAbsolutePath().normalize();
		try {
			Files.createDirectories(fileStorageLocation);
		} catch (Exception ex) {
			throw new CustomException(" Could not create the directory where the uploaded files will be stored. Path = "+fileStorageLocation,
					ex);
		}
	}

	public static String storeFile(MultipartFile file,String path, String fileName) throws CustomException {
		setPath(path);
		fileName = StringUtils.cleanPath(fileName);
		try {
			// Check if the file's name contains invalid characters
			if (fileName.contains("..")) {
				throw new CustomException("Sorry! Filename contains invalid path sequence " + fileName, new Exception());
			}

			// Copy file to the target location (Replacing existing file with the same name)
			Path targetLocation = fileStorageLocation.resolve(fileName);
			Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

			return path + fileName;
		} catch (IOException ex) {
			throw new CustomException("Could not store file " + fileName + ". Please try again!", ex);
		}
	}

	public static String createThumbnailIfImage(MultipartFile file, Long id, String originalFileName) throws CustomException {
		try {
			String contentType = file.getContentType();
			if (Objects.isNull(contentType) || !contentType.toLowerCase().startsWith("image/")
					|| contentType.equalsIgnoreCase("image/svg+xml")) {
				return null;
			}

			BufferedImage sourceImage = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
			if (Objects.isNull(sourceImage)) {
				return null;
			}

			int width = sourceImage.getWidth();
			int height = sourceImage.getHeight();
			int maxDimension = 250;
			double scale = Math.min(1.0d, Math.min((double) maxDimension / width, (double) maxDimension / height));
			int thumbWidth = Math.max(1, (int) Math.round(width * scale));
			int thumbHeight = Math.max(1, (int) Math.round(height * scale));

			int imageType = sourceImage.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : sourceImage.getType();
			BufferedImage thumbnailImage = new BufferedImage(thumbWidth, thumbHeight, imageType);
			Graphics2D graphics = thumbnailImage.createGraphics();
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(sourceImage, 0, 0, thumbWidth, thumbHeight, null);
			graphics.dispose();

			String format = "png";
			if (contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/jpg")) {
				format = "jpg";
			}

			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			if (!ImageIO.write(thumbnailImage, format, outputStream)) {
				return null;
			}

			String thumbnailFileName = id + "_" + originalFileName;
			MultipartFile thumbnailFile = new InMemoryMultipartFile(
					"file",
					thumbnailFileName,
					"image/" + format,
					outputStream.toByteArray()
			);
			return storeFile(thumbnailFile, "UserChecksheetAnswerFile/thumbnail/", thumbnailFileName);
		} catch (Exception e) {
			throw new CustomException("Failed to create thumbnail", e);
		}
	}

	private static class InMemoryMultipartFile implements MultipartFile {
		private final String name;
		private final String originalFilename;
		private final String contentType;
		private final byte[] content;

		public InMemoryMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
			this.name = name;
			this.originalFilename = originalFilename;
			this.contentType = contentType;
			this.content = content;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getOriginalFilename() {
			return originalFilename;
		}

		@Override
		public String getContentType() {
			return contentType;
		}

		@Override
		public boolean isEmpty() {
			return content.length == 0;
		}

		@Override
		public long getSize() {
			return content.length;
		}

		@Override
		public byte[] getBytes() {
			return content;
		}

		@Override
		public ByteArrayInputStream getInputStream() {
			return new ByteArrayInputStream(content);
		}

		@Override
		public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
			Files.write(dest.toPath(), content);
		}
	}
	
	public static Resource loadFileAsResource(String path,String fileName) throws CustomException {
		try {
			Resource resource = new UrlResource(getResourcePath(path, fileName).toUri());
			if (resource.exists()) {
				return resource;
			} else {
				throw new CustomException("File not found " + fileName, new Exception());
			}
		} catch (MalformedURLException ex) {
			throw new CustomException("File not found " + fileName, ex);
		}
	}
	
	
	public static void deleteFile(String path,String fileName) throws CustomException {
		try {
		    Files.deleteIfExists(getResourcePath(path, fileName));
		} catch (IOException x) {
		    throw new CustomException("File is not allowed to delete " + fileName, x);
		}
	}

	public static void deleteFile(String filePath) throws CustomException {
		try {
			Path path = Paths.get(filePath);
			String directoryPath = path.getParent() != null ? path.getParent().toString() : "";
			String fileName = path.getFileName().toString();
			Files.deleteIfExists(getResourcePath(directoryPath, fileName));
		} catch (IOException x) {
			throw new CustomException("File is not allowed to delete " + filePath, x);
		}
	}
	
	public static Path getResourcePath(String path,String fileName) {
		fileStorageLocation = Paths.get(fileStorageProperties.getUploadDir() + path).toAbsolutePath().normalize();
        return fileStorageLocation.resolve(fileName).normalize();
	}

	public static byte[] getFileAsByteArray(String filePath) throws IOException {
		Path path = Paths.get(filePath);
		String directoryPath = path.getParent() != null ? path.getParent().toString() : "";
		String fileName = path.getFileName().toString();
		return Files.readAllBytes(getResourcePath(directoryPath, fileName));
	}

	public static ResponseEntity<Resource> getDocs(String path, String fileName, HttpServletRequest request) throws CustomException{
		Resource resource = loadFileAsResource(path,fileName);

		// Try to determine file's content type
        String contentType = null;
        try {
            contentType = request.getServletContext().getMimeType(resource.getFile().getAbsolutePath());
        } catch (IOException ex) {
           // logger.info("Could not determine file type.");
        }

        // Fallback to the default content type if type could not be determined
        if(contentType == null) {
            contentType = "application/octet-stream";
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);

	}
	
	public static Path getBasePath(String path) {
		return Paths.get(fileStorageProperties.getUploadDir() + path).toAbsolutePath().normalize();
	}
	
	public static Path moveFile(String fromPath,String toPath,String directoryPath) throws IOException, CustomException {
		setPath(directoryPath);
		Path temp = Files.move (Paths.get(fromPath), Paths.get(toPath));
		return temp; 
	}
	
	public static Set<String> listFilesUsingDirectoryStream(String dir) throws IOException {
	    Set<String> fileList = new HashSet<>();
	    try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(dir))) {
	        for (Path path : stream) {
	            if (!Files.isDirectory(path)) {
	            	Optional<String> fileExt = getExtensionByStringHandling(path.getFileName().toString());
	            	System.out.println(fileExt.get().equals("csv"));
	            	if(fileExt.get().equals("csv")) {
	            		fileList.add(path.getFileName().toString());
	            	}
	            }
	        }
	    }
	    return fileList;
	} 
	
	public static Optional<String> getExtensionByStringHandling(String filename) {
	    return Optional.ofNullable(filename)
	      .filter(f -> f.contains("."))
	      .map(f -> f.substring(filename.lastIndexOf(".") + 1));
	}
	
	public static File getMostRecentFile(String dir) {
		Path parentFolder = Paths.get(dir);
		Optional<File> mostRecentFile = Arrays.stream(Objects.requireNonNull(parentFolder.toFile().listFiles()))
									        .filter(File::isFile)
									        .filter(f ->
									        	getExtensionByStringHandling(f.getName()).get().equals("csv")
									        ).max((f1, f2) -> Long.compare(f1.lastModified(),f2.lastModified()));
        return mostRecentFile.orElse(null);
	}
	
	public static void copyDirectory(String sourceDirectoryLocation, String destinationDirectoryLocation) throws IOException {
	    Files.walk(Paths.get(sourceDirectoryLocation)).forEach(source -> {
    		Path destination = Paths.get(destinationDirectoryLocation, source.toString().substring(sourceDirectoryLocation.length()));
			try {
				Files.copy(source, destination);
			} catch (IOException e) {
			    e.printStackTrace();
			}
	    });
	}
	
	public static void copyFile(String sourcePath, String destinationPath) throws IOException {
		Path source = Paths.get(fileStorageProperties.getUploadDir() + sourcePath).toAbsolutePath().normalize();
	    Path destination = Paths.get(fileStorageProperties.getUploadDir() + destinationPath).toAbsolutePath().normalize(); //"src/test/resources/copiedWithNio.txt"
	    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
	}
	
	public static void zipFileOfFiles(List<String> srcFiles, String zipFileName) throws IOException {
        FileOutputStream fos = new FileOutputStream(zipFileName);
        ZipOutputStream zipOut = new ZipOutputStream(fos);
        for (String srcFile : srcFiles) {
            File fileToZip = new File(srcFile);
            FileInputStream fis = new FileInputStream(fileToZip);
            ZipEntry zipEntry = new ZipEntry(fileToZip.getName());
            zipOut.putNextEntry(zipEntry);
 
            byte[] bytes = new byte[1024];
            int length;
            while((length = fis.read(bytes)) >= 0) {
                zipOut.write(bytes, 0, length);
            }
            fis.close();
        }
        zipOut.close();
        fos.close();
	}

	public static String getFileURL(String filePath){
		return ServletUriComponentsBuilder.fromCurrentContextPath()
				.scheme(protocol)  // Force HTTPS
				.path("/api/checksheet/doc/" + filePath)
				.toUriString();
	}
}