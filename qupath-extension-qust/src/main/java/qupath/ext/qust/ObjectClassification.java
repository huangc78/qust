/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.qust;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractTileableDetectionPlugin;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Default command for DL-based object classification within QuPath.
 * 
 * @author Chao Hui Huang
 *
 */
public class ObjectClassification extends AbstractTileableDetectionPlugin<BufferedImage> {
	private static final StringProperty QuSTObjclsModelNameProp = PathPrefs.createPersistentPreference("QuSTObjclsModelName", null);
	private static final BooleanProperty QuSTObjclsDetectionProp = PathPrefs.createPersistentPreference("QuSTObjclsDetection", true);		
	
	protected boolean parametersInitialized = false;

	private transient CellClassifier detector;
	
	private static QuSTSetup qustSetup = QuSTSetup.getInstance();
	private static final Logger logger = LoggerFactory.getLogger(ObjectClassification.class);
	
	private static int modelFeatureSizePixels;
	private static double modelPixelSizeMicrons;
	private static boolean modelNormalized;
	private static List<String> modelLabelList;
	private static String modelName;
	private static List<PathObject> availabelObjList;
	
	private static Semaphore semaphore;
	protected ParameterList params;
	private static double[] normalizer_w = null;
	private static final AtomicInteger hackDigit = new AtomicInteger(0);
	private static final String imgFmt = qustSetup.getImageFileFormat().trim().charAt(0) == '.'? qustSetup.getImageFileFormat().trim().substring(1): qustSetup.getImageFileFormat().trim();
	
	static class CellClassifier implements ObjectDetector<BufferedImage> {
	
		protected String lastResultDesc = null;
		private List<PathObject> pathObjects = null;
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			Path imageSetPath = null;
			Path resultPath = null;		
			
			try {
				QuSTObjclsModelNameProp.set((String)params.getChoiceParameterValue("modelName"));
				QuSTObjclsDetectionProp.set(params.getBooleanParameterValue("includeProbability"));
				
				if (pathROI == null) throw new IOException("Object classification requires a ROI!");
				if(availabelObjList.size() == 0) new IOException("No objects are selected!");
				
				final ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
				final String serverPath = server.getPath();			
				final RegionRequest tileRegion = RegionRequest.createInstance(server.getPath(), 1.0, pathROI);
				
		    	pathObjects = Collections.synchronizedList(new ArrayList<PathObject>());
		    	
				availabelObjList.parallelStream().forEach( objObject -> {
					final ROI objRoi = objObject.getROI();
					final int x = (int)(0.5+objRoi.getCentroidX());
					final int y = (int)(0.5+objRoi.getCentroidY());
					
					if(tileRegion.contains(x, y, 0, 0)) {
						synchronized(pathObjects) {
							pathObjects.add(objObject);
						}
					}
				});			
				
				if(pathObjects.size() > 0) {
					// Create a temporary directory for imageset
					final String uuid = UUID.randomUUID().toString().replace("-", "")+hackDigit.getAndIncrement()+tileRegion.getMinX()+tileRegion.getMinY();
					
					imageSetPath = Files.createTempDirectory("QuST-classification_imageset-" + uuid + "-");
					final String imageSetPathString = imageSetPath.toAbsolutePath().toString();
                    imageSetPath.toFile().deleteOnExit();
        			
                    resultPath = Files.createTempFile("QuST-classification_result-" + uuid + "-", ".json");
                    final String resultPathString = resultPath.toAbsolutePath().toString();
                    resultPath.toFile().deleteOnExit();

        			final String modelLocationStr = qustSetup.getObjclsModelLocationPath();
        			final String modelPathStr = Paths.get(modelLocationStr, modelName+".pt").toString();
        			
                    final double imagePixelSizeMicrons = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
                    final int FeatureSizePixels = (int)(0.5+modelFeatureSizePixels*modelPixelSizeMicrons/imagePixelSizeMicrons);
                                        	
                    IntStream.range(0, pathObjects.size()).forEach(i -> { 
                    // for(int i = 0; i < pathObjects.size(); i ++) {
						final PathObject objObject = pathObjects.get(i);
						final ROI objRoi = objObject.getROI();
					    final int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double)FeatureSizePixels / 2.0));
					    final int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double)FeatureSizePixels / 2.0));
					    final RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0, FeatureSizePixels, FeatureSizePixels);
						
						try {
							// Read image patches from server
							final BufferedImage readImg = (BufferedImage)server.readRegion(objRegion);
							final BufferedImage bufImg = new BufferedImage(readImg.getWidth(), readImg.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
							bufImg.getGraphics().drawImage(readImg, 0, 0, null);
							
							//  Assign a file name by sequence
							final String imageFileName = Integer.toString(i)+"."+imgFmt;
							
							// Obtain the absolute path of the given image file name (with the predefined temporary imageset path)
							final Path imageFilePath = Paths.get(imageSetPathString, imageFileName);
							
							// Make the image file
							File imageFile = new File(imageFilePath.toString());
							ImageIO.write(bufImg, imgFmt, imageFile);
						} 
						catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					});
                    // }
					
                    if(semaphore != null) semaphore.acquire();
                    
					// Create command to run
			        VirtualEnvironmentRunner veRunner;
			        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), ObjectClassification.class.getSimpleName());
				
			        // This is the list of commands after the 'python' call
			        final String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
			        List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "eval", resultPathString));
			        
			        QuSTArguments.add("--model_file");
			        QuSTArguments.add(modelPathStr);
			        veRunner.setArguments(QuSTArguments);
			        
			        QuSTArguments.add("--image_path");
			        QuSTArguments.add(imageSetPathString);
			        veRunner.setArguments(QuSTArguments);

			        QuSTArguments.add("--image_format");
			        QuSTArguments.add(imgFmt);
			        veRunner.setArguments(QuSTArguments);
			        
			        QuSTArguments.add("--batch_size");
			        QuSTArguments.add(params.getIntParameterValue("batchSize").toString());
			        veRunner.setArguments(QuSTArguments);
			        
			        if(modelNormalized) {
				        QuSTArguments.add("--normalizer_w");
				        QuSTArguments.add(String.join(" ", Arrays.stream(normalizer_w).boxed().map(Object::toString).collect(Collectors.toList())));
				        veRunner.setArguments(QuSTArguments);
			        }
			        
			        // Finally, we can run the command
			        final String[] logs = veRunner.runCommand();
			        for (String log : logs) logger.info(log);
			        // logger.info("Object classification command finished running");
					
					if(semaphore != null) semaphore.release();
					
					final FileReader resultFileReader = new FileReader(new File(resultPathString));
					final BufferedReader bufferedReader = new BufferedReader(resultFileReader);
					final Gson gson = new Gson();
					final JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
					
					final Boolean ve_success = gson.fromJson(jsonObject.get("success"), new TypeToken<Boolean>(){}.getType());
					if(!ve_success) throw new Exception("classification.py returned failed");
					
					final List<Double> ve_predicted = gson.fromJson(jsonObject.get("predicted"), new TypeToken<List<Double>>(){}.getType());
					if(ve_predicted == null) throw new Exception("classification.py returned null");
					if(ve_predicted.size() != pathObjects.size()) throw new Exception("classification.py returned wrong size");
					
					final List<List<Double>> ve_prob_dist = gson.fromJson(jsonObject.get("probability"), new TypeToken<List<List<Double>>>(){}.getType());
					
					if(ve_prob_dist == null) throw new Exception("classification.py returned null");
					if(ve_prob_dist.size() != pathObjects.size()) throw new Exception("classification.py returned wrong size");
					
					IntStream.range(0, ve_predicted.size()).parallel().forEach(i -> {
//					for(int ii = 0; ii < ve_predicted.size(); ii ++) {
//						final int i=ii;
						final PathClass pc = PathClass.fromString("objcls:"+modelName+":"+modelLabelList.get(ve_predicted.get(i).intValue()));
						pathObjects.get(i).setPathClass(pc);
						
						if(params.getBooleanParameterValue("includeProbability")) {
							final MeasurementList pathObjMeasList = pathObjects.get(i).getMeasurementList();
//							pathObjMeasList.put("objcls:"+modelName+":pred", ve_predicted.get(i).intValue());  
							
							IntStream.range(0, modelLabelList.size()).parallel().forEach(k -> {
//							for(int kk = 0; kk < modelLabelList.size(); kk ++) {
//								final int k = kk;
								synchronized(pathObjMeasList) {
									pathObjMeasList.put("objcls:"+modelName+":prob:"+modelLabelList.get(k), ve_prob_dist.get(i).get(k));  
								}
							});
//							}
							
							pathObjMeasList.close();
						}
					});
//					}
				}
		    }
			catch (Exception e) {				    	
				e.printStackTrace();
				
			}
		    finally {
                if(imageSetPath != null) imageSetPath.toFile().delete();
                if(resultPath != null) resultPath.toFile().delete();			    
                
                System.gc();
		    }

			return pathObjects;
		}
		
		
		public static double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
			PixelCalibration cal = imageData.getServer().getPixelCalibration();
			if (cal.hasPixelSizeMicrons()) {

				return cal.getAveragedPixelSizeMicrons();
			}
			return Double.NaN;
		}
		
		@Override
		public String getLastResultsDescription() {
			return lastResultDesc;
		}
		
	}
	
	
	private ParameterList buildParameterList(final ImageData<BufferedImage> imageData) { 
			
		ParameterList params = new ParameterList();
		// TODO: Use a better way to determining if pixel size is available in microns

		try {			
			if(!imageData.getServer().getPixelCalibration().hasPixelSizeMicrons()) {
				Dialogs.showErrorMessage("Error", "Please check the image properties in left panel. Most likely the pixel size is unknown.");
				throw new Exception("No pixel size information");
			}
	        
			final List<String> classificationModeNamelList = Files.list(Paths.get(qustSetup.getObjclsModelLocationPath()))
					.filter(Files::isRegularFile)
            	    .map(p -> p.getFileName().toString())
            	    .filter(s -> s.endsWith(".pt"))
            	    .map(s -> s.replaceAll("\\.pt", ""))
            	    .collect(Collectors.toList());

			if(classificationModeNamelList.size() == 0) throw new Exception("No model exist in the model directory.");
			
			params = new ParameterList()
					.addChoiceParameter("modelName", "Model", QuSTObjclsModelNameProp.get() == null? classificationModeNamelList.get(0): QuSTObjclsModelNameProp.get(), classificationModeNamelList, 
					"Choose the model that should be used for object classification")
					.addBooleanParameter("includeProbability", "Add prediction/probability as a measurement (enables later filtering). Default: false", QuSTObjclsDetectionProp.get(), "Add probability as a measurement (enables later filtering)")
					.addEmptyParameter("")
					.addEmptyParameter("Adjust below parameters if GPU resources are limited.")
					.addIntParameter("batchSize", "Batch Size in classification (default: 128)", 128, null, "Batch size in classification. The larger the faster. However, a larger batch size results larger GPU memory consumption.")		
					.addIntParameter("maxThread", "Max number of parallel threads (0: using qupath setup)", 0, null, "Max number of parallel threads (0: using qupath setup)");	
					
		} catch (Exception e) {
			params = null;
			
			// TODO Auto-generated catch block
			e.printStackTrace();
			Dialogs.showErrorMessage("Error", e.getMessage());
		} finally {
		    System.gc();
		}
		
		return params;
	}
	
	
	private double[] estimate_w (final ImageData<BufferedImage> imageData)  {
		double [] W = null;
		
		try {
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();

			final List<PathObject> selectedAnnotationPathObjectList = Collections.synchronizedList(
					hierarchy
					.getSelectionModel()
					.getSelectedObjects()
					.stream()
					.filter(e -> e.isAnnotation())
					.collect(Collectors.toList())
					);

			if (selectedAnnotationPathObjectList.isEmpty())
				throw new Exception("Missed selected annotations");

			final ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
			final String serverPath = server.getPath();

			final AtomicBoolean success = new AtomicBoolean(true);
			
			final String uuid = UUID.randomUUID().toString().replace("-", "");
			
			final Path imageSetPath = Files.createTempDirectory("QuST-estimate_w-" + uuid + "-");
            final String imageSetPathString = imageSetPath.toAbsolutePath().toString();
            imageSetPath.toFile().deleteOnExit();
            
			final Path resultPath = Files.createTempFile("QuST-classification_result-" + uuid + "-", ".json");
            final String resultPathString = resultPath.toAbsolutePath().toString();
            resultPath.toFile().deleteOnExit();
			
			final List<PathObject> allPathObjects = Collections.synchronizedList(new ArrayList<PathObject>());

			for (PathObject sltdObj : selectedAnnotationPathObjectList) {
				allPathObjects.addAll(sltdObj.getChildObjects());
			}

			
			if(allPathObjects.size() < qustSetup.getNormalizationSampleSize()) throw new Exception("Number of available object samples is too small."); 
			
			Collections.shuffle(allPathObjects);
			final List<PathObject> samplingPathObjects = Collections.synchronizedList(allPathObjects.subList(0, qustSetup.getNormalizationSampleSize()));

			IntStream.range(0, samplingPathObjects.size()).parallel().forEach(i -> {
//			for(int i = 0; i < samplingPathObjects.size(); i ++) {
				final PathObject objObject = samplingPathObjects.get(i);
				final ROI objRoi = objObject.getROI();

				final int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) modelFeatureSizePixels / 2.0));
				final int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) modelFeatureSizePixels / 2.0));
				final RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0,
						modelFeatureSizePixels, modelFeatureSizePixels);

				try {
					final BufferedImage imgContent = (BufferedImage) server.readRegion(objRegion);
					final BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
					
					imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
					
					final Path imageFilePath = Paths.get(imageSetPathString, objObject.getID().toString() + "." + imgFmt);
					
					final File imageFile = new File(imageFilePath.toString());
					ImageIO.write(imgBuf, imgFmt, imageFile);
				} catch (Exception e) {
					success.set(false);
					e.printStackTrace();
				}
			});
//			}

			// Create command to run
	        VirtualEnvironmentRunner veRunner;
	        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), RegionSegmentation.class.getSimpleName());
		
	        // This is the list of commands after the 'python' call
	        final String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
	        List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "estimate_w", resultPathString));
	        
	        QuSTArguments.add("--image_path");
	        QuSTArguments.add(imageSetPathString);
	        veRunner.setArguments(QuSTArguments);
	        
	        // Finally, we can run the command
	        final String[] logs = veRunner.runCommand();
	        for (String log : logs) logger.info(log);
			
			final FileReader resultFileReader = new FileReader(new File(resultPathString));
			final BufferedReader bufferedReader = new BufferedReader(resultFileReader);
			final Gson gson = new Gson();
			final JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
			
			final Boolean ve_success = gson.fromJson(jsonObject.get("success"), new TypeToken<Boolean>(){}.getType());
			if(!ve_success) throw new Exception("classification.py returned failed");
			
			final List<Double> ve_result = gson.fromJson(jsonObject.get("W"), new TypeToken<List<Double>>(){}.getType());
			
			if(ve_result == null) throw new Exception("classification.py returned null");

			W = ve_result.stream().mapToDouble(Double::doubleValue).toArray();
			
			imageSetPath.toFile().delete();
			resultPath.toFile().delete();
		} catch (Exception e) {
			Dialogs.showErrorMessage("Error", e.getMessage());
			e.printStackTrace();
		} finally {
			System.gc();
		}
		
		return W;
	}
	
	
	@Override
	protected void preprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		try {
			modelName = (String)getParameterList(imageData).getChoiceParameterValue("modelName");
			final String modelLocationStr = qustSetup.getObjclsModelLocationPath();
			final String modelPathStr = Paths.get(modelLocationStr, modelName+".pt").toString();
			final String uuid = UUID.randomUUID().toString().replace("-", "");
			final Path resultPath = Files.createTempFile("QuST-classification_result-" + uuid + "-", ".json");
            final String resultPathString = resultPath.toAbsolutePath().toString();
            resultPath.toFile().deleteOnExit();
            
			// Create command to run
	        VirtualEnvironmentRunner veRunner;
			
			veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), ObjectClassification.class.getSimpleName());
		
	        // This is the list of commands after the 'python' call
			final String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
			
			List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "param", resultPathString));
			
	        QuSTArguments.add("--model_file");
	        QuSTArguments.add("" + modelPathStr);
	        veRunner.setArguments(QuSTArguments);

	        // Finally, we can run Cellpose
	        final String[] logs = veRunner.runCommand();
	        for (String log : logs) logger.info(log);
			
	        final FileReader resultFileReader = new FileReader(new File(resultPathString));
			final BufferedReader bufferedReader = new BufferedReader(resultFileReader);
			final Gson gson = new Gson();
			final JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
			
			modelPixelSizeMicrons = jsonObject.get("pixel_size").getAsDouble();
			modelNormalized = jsonObject.get("normalized").getAsBoolean();
			modelFeatureSizePixels = jsonObject.get("image_size").getAsInt();
			modelLabelList = Arrays.asList(jsonObject.get("label_list").getAsString().split(";"));
			
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			final Collection<PathObject> selectedObjects = hierarchy.getSelectionModel().getSelectedObjects();
			final Predicate<PathObject> pred = p -> selectedObjects.contains(p.getParent());
			
			availabelObjList = Collections.synchronizedList(QPEx.getObjects(hierarchy, pred));
			
			if(availabelObjList.size() < qustSetup.getNormalizationSampleSize())throw new Exception("Requires more samples for estimating H&E staining.");
			
			final int maxThread = getParameterList(imageData).getIntParameterValue("maxThread");
			semaphore = maxThread > 0? new Semaphore(maxThread): null;
			
			if(modelNormalized) normalizer_w = estimate_w(imageData);
		} catch (Exception e) {
			e.printStackTrace();
			
			Dialogs.showErrorMessage("Error", e.getMessage());
		} finally {
		    System.gc();
		}
	}	


	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		if (!parametersInitialized) {
			params = buildParameterList(imageData);
		}
	
		return params;
	}

	
	@Override
	public String getName() {
		return "Cell-subtype classification";
	}

	
	@Override
	public String getLastResultsDescription() {
		return detector == null ? "" : detector.getLastResultsDescription();
	}

	
	@Override
	public String getDescription() {
		return "Cell subtype classification based on machine leatning algorithms";
	}


	@Override
	protected double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
		return CellClassifier.getPreferredPixelSizeMicrons(imageData, params);
	}


	@Override
	protected ObjectDetector<BufferedImage> createDetector(ImageData<BufferedImage> imageData, ParameterList params) {
		return new CellClassifier();
	}
		
	
	@Override
	protected int getTileOverlap(ImageData<BufferedImage> imageData, ParameterList params) {
		return 0;
	}
}
