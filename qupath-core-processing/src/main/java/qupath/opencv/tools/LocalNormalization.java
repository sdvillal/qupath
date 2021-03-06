package qupath.opencv.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

import qupath.lib.common.GeneralTools;
import qupath.lib.images.servers.PixelCalibration;

/**
 * Methods to normalize the local image intensity within an image, to have (approximately) zero mean and unit variance.
 * Calculations are made using Gaussian filters to give a smooth result.
 * 
 * @author Pete Bankhead
 */
public class LocalNormalization {
	
	public static enum NormalizationType {
		NONE,
		GAUSSIAN_MEAN_ONLY,
		GAUSSIAN_MEAN_VARIANCE
	}
	
	public static class LocalNormalizationType {
		
		final public SmoothingScale scale;
		final public SmoothingScale scaleVariance;
		
//		final private boolean subtractOnly;

		private LocalNormalizationType(SmoothingScale scale, SmoothingScale scaleVariance) {
			this.scale = scale;
			this.scaleVariance = scaleVariance;
		}

		/**
		 * Get an object containing the parameters necessary for normalization.
		 * 
		 * @param scale Gaussian sigma value used for initial filters (mean subtraction)
		 * @param scaleVariance sigma value used for variance estimation (may be null to apply subtraction only)
		 * @return
		 */
		public static LocalNormalizationType getInstance(SmoothingScale scale, SmoothingScale scaleVariance) {
			Objects.nonNull(scale);
			return new LocalNormalizationType(scale, scaleVariance);
		}
		
		public static LocalNormalizationType getInstance(SmoothingScale scale, double varianceScaleRatio) {
			Objects.nonNull(scale);
			if (varianceScaleRatio <= 0)
				return getInstance(scale, null);
			return getInstance(scale, SmoothingScale.getInstance(scale.scaleType, scale.getSigma() * varianceScaleRatio));
		}
		
	}
	
	
	/**
	 * Define how filters should be applied to 2D images and z-stacks when calculating multiscale features.
	 */
	static enum ScaleType { 
		/**
		 * Apply 2D filters.
		 */
		SCALE_2D,
		/**
		 * Apply 3D filters where possible.
		 */
		SCALE_3D,
		/**
		 * Apply 3D filters where possible, correcting for anisotropy in z-resolution to match xy resolution.
		 */
		SCALE_3D_ISOTROPIC
	}
	
	
	public static class SmoothingScale {
		
		final private double sigma;
		final private ScaleType scaleType;
		
		private SmoothingScale(ScaleType scaleType, double sigma) {
			this.sigma = sigma;
			this.scaleType = scaleType;
		}
		
		public static SmoothingScale get2D(double sigma) {
			return getInstance(ScaleType.SCALE_2D, sigma);
		}
		
		public static SmoothingScale get3DAnisotropic(double sigma) {
			return getInstance(ScaleType.SCALE_3D, sigma);
		}
		
		public static SmoothingScale get3DIsotropic(double sigma) {
			return getInstance(ScaleType.SCALE_3D_ISOTROPIC, sigma);
		}
		
		static SmoothingScale getInstance(ScaleType scaleType, double sigma) {
			return new SmoothingScale(scaleType, sigma);
		}
		
//		public ScaleType getScaleType() {
//			return scaleType;
//		}
		
		public double getSigma() {
			return sigma;
		}
		
		public double getSigmaZ(PixelCalibration cal) {
			switch (scaleType) {
			case SCALE_2D:
				return 0;
			case SCALE_3D:
				return sigma;
			case SCALE_3D_ISOTROPIC:
				double pixelSize = cal.getAveragedPixelSize().doubleValue();
				double zSpacing = cal.getZSpacing().doubleValue();
				if (!Double.isFinite(zSpacing))
					zSpacing = 1.0;
				return sigma / zSpacing * pixelSize;
			default:
				throw new IllegalArgumentException("Unknown smoothing scale " + sigma);
			}
		}
		
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((scaleType == null) ? 0 : scaleType.hashCode());
			long temp;
			temp = Double.doubleToLongBits(sigma);
			result = prime * result + (int) (temp ^ (temp >>> 32));
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SmoothingScale other = (SmoothingScale) obj;
			if (scaleType != other.scaleType)
				return false;
			if (Double.doubleToLongBits(sigma) != Double.doubleToLongBits(other.sigma))
				return false;
			return true;
		}

		@Override
		public String toString() {
			String sigmaString = String.format("\u03C3: %s", GeneralTools.formatNumber(sigma, 2));
			switch (scaleType) {
			case SCALE_3D:
				return sigmaString + " (3D)";
			case SCALE_3D_ISOTROPIC:
				return sigmaString + " (3D isotropic)";
			case SCALE_2D:
			default:
				return sigmaString;
			}
		}
		
	}

	/**
	 * Apply local normalization to a stack of Mats representing a z-stack.
	 * @param stack
	 * @param type
	 * @param cal
	 * @param border
	 */
	public static void gaussianNormalize(List<Mat> stack, LocalNormalizationType type, PixelCalibration cal, int border) {
		double sigmaX = type.scale.getSigma();
		double sigmaY = type.scale.getSigma();
		double sigmaZ = type.scale.getSigmaZ(cal);
		
		double sigmaVarianceX = 0, sigmaVarianceY = 0, sigmaVarianceZ = 0;
		if (type.scaleVariance != null) {
			sigmaVarianceX = type.scaleVariance.getSigma();
			sigmaVarianceY = type.scaleVariance.getSigma();
			sigmaVarianceZ = type.scaleVariance.getSigmaZ(cal);
		}
		
		gaussianNormalize3D(stack, sigmaX, sigmaY, sigmaZ, sigmaVarianceX, sigmaVarianceY, sigmaVarianceZ, border);
	}
	
	
	/**
	 * Apply local normalization to a 2D Mat.
	 * @param mat
	 * @param sigma
	 * @param sigmaVariance
	 * @param border
	 */
	public static void gaussianNormalize2D(Mat mat, double sigma, double sigmaVariance, int border) {
		LocalNormalizationType type = LocalNormalizationType.getInstance(
				SmoothingScale.get2D(sigma),
				sigmaVariance > 0 ? SmoothingScale.get2D(sigmaVariance) : null);
		gaussianNormalize(Collections.singletonList(mat), type, PixelCalibration.getDefaultInstance(), border);
	}
	
	
	/**
	 * Apply 3D normalization.
	 * <p>
	 * The algorithm works as follows:
	 * <ol>
	 *   <li>A Gaussian filter is applied to a duplicate of the image</li>
	 *   <li>The filtered image is subtracted from the original</li>
	 *   <li>The subtracted image is duplicated, squared, Gaussian filtered, and the square root taken to create a normalization image</li>
	 *   <li>The subtracted image is divided by the value of the normalization image</li>
	 * </ol>
	 * The resulting image can be thought of as having a local mean of approximately zero and unit variance, 
	 * although this is not exactly true. The approach aims to be simple, efficient and yield an image that does not 
	 * introduce sharp discontinuities by is reliance on Gaussian filters.
	 * 
	 * @param stack image z-stack, in which each element is a 2D (x,y) slice
	 * @param sigmaX horizontal Gaussian filter sigma
	 * @param sigmaY vertical Gaussian filter sigma
	 * @param sigmaZ z-dimension Gaussian filter sigma
	 * @param varianceSigmaX horizontal Gaussian filter sigma for variance estimation
	 * @param varianceSigmaY vertical Gaussian filter sigma for variance estimation
	 * @param varianceSigmaZ z-dimension Gaussian filter sigma for variance estimation
	 * @param border border padding method to use (see OpenCV for definitions)
	 */
	public static void gaussianNormalize3D(List<Mat> stack, double sigmaX, double sigmaY, double sigmaZ,
			double varianceSigmaX, double varianceSigmaY, double varianceSigmaZ, int border) {
		
		Mat kx = OpenCVTools.getGaussianDerivKernel(sigmaX, 0, false);
		Mat ky = OpenCVTools.getGaussianDerivKernel(sigmaY, 0, true);
		Mat kz = OpenCVTools.getGaussianDerivKernel(sigmaZ, 0, false);

		boolean doVariance = varianceSigmaX > 0 || varianceSigmaY > 0 || varianceSigmaZ > 0;
		Mat kx2 = kx;
		Mat ky2 = ky;
		Mat kz2 = kz;
		if (doVariance) {
			kx2 = OpenCVTools.getGaussianDerivKernel(varianceSigmaX, 0, false);
			ky2 = OpenCVTools.getGaussianDerivKernel(varianceSigmaY, 0, true);
			kz2 = OpenCVTools.getGaussianDerivKernel(varianceSigmaZ, 0, false);			
		}

		// Ensure we have float images & their squared versions
		List<Mat> stackSquared = new ArrayList<>();
		for (Mat mat : stack) {
			mat.convertTo(mat, opencv_core.CV_32F);
			if (doVariance)
				stackSquared.add(mat.mul(mat).asMat());
		}
		
		// Apply z-filtering if required, otherwise clone for upcoming smoothing
		List<Mat> stackSmoothed;
		if (sigmaZ > 0) {
			stackSmoothed = OpenCVTools.filterZ(stack, kz, -1, border);
			if (doVariance)
				stackSquared = OpenCVTools.filterZ(stackSquared, kz2, -1, border);
		} else
			stackSmoothed = stack.stream().map(m -> m.clone()).collect(Collectors.toList());
		
		// Complete separable filtering & subtract from original
		for (int i = 0; i < stack.size(); i++) {
			Mat mat = stack.get(i);
			
			// Smooth the image & subtract it from the original
			Mat matSmooth = stackSmoothed.get(i);
			opencv_imgproc.sepFilter2D(matSmooth, matSmooth, opencv_core.CV_32F, kx, ky, null, 0.0, border);
			opencv_core.subtract(mat, matSmooth, mat);

			if (doVariance) {
				// Square the smoothed image
				matSmooth.put(matSmooth.mul(matSmooth));
	
				// Smooth the squared image
				Mat matSquaredSmooth = stackSquared.get(i);
				opencv_imgproc.sepFilter2D(matSquaredSmooth, matSquaredSmooth, opencv_core.CV_32F, kx2, ky2, null, 0.0, border);
				
				opencv_core.subtract(matSquaredSmooth, matSmooth, matSmooth);
				opencv_core.sqrt(matSmooth, matSmooth);
				
				opencv_core.divide(mat, matSmooth, mat);
				
				matSquaredSmooth.release();
			}
			matSmooth.release();
		}
	}
	

//	/**
//	 * Apply 3D normalization.
//	 * <p>
//	 * The algorithm works as follows:
//	 * <ol>
//	 *   <li>A Gaussian filter is applied to a duplicate of the image</li>
//	 *   <li>The filtered image is subtracted from the original</li>
//	 *   <li>The subtracted image is duplicated, squared, Gaussian filtered, and the square root taken to create a normalization image</li>
//	 *   <li>The subtracted image is divided by the value of the normalization image</li>
//	 * </ol>
//	 * The resulting image can be thought of as having a local mean of approximately zero and unit variance, 
//	 * although this is not exactly true. The approach aims to be simple, efficient and yield an image that does not 
//	 * introduce sharp discontinuities by is reliance on Gaussian filters.
//	 * 
//	 * @param stack image z-stack, in which each element is a 2D (x,y) slice
//	 * @param sigmaX horizontal Gaussian filter sigma
//	 * @param sigmaY vertical Gaussian filter sigma
//	 * @param sigmaZ z-dimension Gaussian filter sigma
//	 * @param border border padding method to use (see OpenCV for definitions)
//	 */
//	public static void gaussianNormalize3D(List<Mat> stack, double sigmaX, double sigmaY, double sigmaZ, int border) {
//		
//		Mat kx = OpenCVTools.getGaussianDerivKernel(sigmaX, 0, false);
//		Mat ky = OpenCVTools.getGaussianDerivKernel(sigmaY, 0, true);
//		Mat kz = OpenCVTools.getGaussianDerivKernel(sigmaZ, 0, false);
//		
//		// Apply z-filtering if required, or clone planes otherwise
//		List<Mat> stack2;
//		if (sigmaZ > 0)
//			stack2 = OpenCVTools.filterZ(stack, kz, -1, border);
//		else
//			stack2 = stack.stream().map(m -> m.clone()).collect(Collectors.toList());
//		
//		// Complete separable filtering & subtract from original
//		for (int i = 0; i < stack.size(); i++) {
//			Mat mat = stack.get(i);
//			mat.convertTo(mat, opencv_core.CV_32F);
//			Mat matSmooth = stack2.get(i);
//			opencv_imgproc.sepFilter2D(matSmooth, matSmooth, opencv_core.CV_32F, kx, ky, null, 0.0, border);
//			opencv_core.subtractPut(mat, matSmooth);
//			// Square the subtracted images & smooth again
//			matSmooth.put(mat.mul(mat));
//			opencv_imgproc.sepFilter2D(matSmooth, matSmooth, opencv_core.CV_32F, kx, ky, null, 0.0, border);
//		}
//		
//		// Complete the 3D smoothing of the squared values
//		if (sigmaZ > 0)
//			stack2 = OpenCVTools.filterZ(stack2, kz, -1, border);
//		
//		// Divide by the smoothed values
//		for (int i = 0; i < stack.size(); i++) {
//			Mat mat = stack.get(i);
//			Mat matSmooth = stack2.get(i);
//			opencv_core.sqrt(matSmooth, matSmooth);
//			mat.put(opencv_core.divide(mat, matSmooth));
//			matSmooth.release();
//		}
//	
//	}

//	/**
//	 * Apply 2D normalization.
//	 * @param mat input image
//	 * @param sigmaX horizontal Gaussian filter sigma
//	 * @param sigmaY vertical Gaussian filter sigma
//	 * @param varianceSigmaRatio ratio of sigma value used when calculating the local variance (typically &ge; 1) if zero, only subtraction is performed
//	 * @param border border padding method to use (see OpenCV for definitions)
//	 * 
//	 * @see #gaussianNormalize3D(List, double, double, double, boolean, int)
//	 */
//	public static void gaussianNormalize2D(Mat mat, double sigmaX, double sigmaY, double varianceSigmaRatio, int border) {
//		gaussianNormalize3D(Collections.singletonList(mat), sigmaX, sigmaY, 0.0, varianceSigmaRatio, border);
//	}

//	/**
//	 * Apply 2D normalization to a list of images.
//	 * This may be a z-stack, but each 2D image (x,y) plane is treated independently.
//	 * @param stack image z-stack, in which each element is a 2D (x,y) slice
//	 * @param sigma horizontal and vertical Gaussian filter
//	 * @param sigmaVariance horizontal and vertical Gaussian filter for variance estimation
//	 * @param border border padding method to use (see OpenCV for definitions)
//	 */
//	public static void gaussianNormalize2D(List<Mat> stack, double sigma, double sigmaVariance, int border) {
//		var scale = SmoothingScale.get2D(sigma);
//		SmoothingScale scaleVariance = sigmaVariance <= 0 ? null : SmoothingScale.get2D(sigmaVariance);
//		var type = LocalNormalizationType.getInstance(scale, scaleVariance);
//		gaussianNormalize3D(stack, type, null, border);
//	}

}
