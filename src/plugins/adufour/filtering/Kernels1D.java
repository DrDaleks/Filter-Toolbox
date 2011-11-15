package plugins.adufour.filtering;

import icy.image.IcyBufferedImage;
import icy.sequence.Sequence;
import icy.type.DataType;

public enum Kernels1D implements IKernel
{
	/** Gradient */
	GRADIENT(new double[] { -0.5, 0, 0.5 }, false),

	/**
	 * Custom gaussian kernel. The "GAUSSIAN" suffix is used only by the graphical user interface.
	 * To fill this kernel with a GAUSSIAN function, call the
	 * {@link #createGaussianKernel1D(double)} method on this empty kernel
	 */
	CUSTOM_GAUSSIAN(null, true),

	/**
	 * Custom kernel for user-defined values. Must be used in conjunction with the
	 * {@link #createCustomKernel(double[], boolean)} method
	 */
	CUSTOM(null, true);
	
	private double[]	data;
	
	private boolean		isSeparable;
	
	Kernels1D(double[] data, boolean isSeparable)
	{
		this.data = data;
	}
	
	public boolean isSeparable()
	{
		return isSeparable;
	}
	
	public Sequence toSequence()
	{
		IcyBufferedImage kernelImage = new IcyBufferedImage(data.length, 1, 1, DataType.DOUBLE);
		kernelImage.setDataXYAsDouble(0, data);
		Sequence kernel = new Sequence(kernelImage);
		kernel.setName(this.toString());
		return kernel;
	}
	
	public double[] getData()
	{
		return data;
	}
	
	/**
	 * Creates a 1D Gaussian kernel (useful for separable convolution) with given standard deviation
	 * (kernel size is automatically computed to fit three standard deviations away from the mean)
	 * 
	 * @param sigma
	 *            the standard deviation of the gaussian
	 * @return the kernel as a sequence, or null if sigma is 0
	 */
	public Kernels1D createGaussianKernel1D(double sigma)
	{
		if (sigma < 1.0e-10)
		{
			this.data = new double[] { 1 };
			return this;
		}
		
		double sigma2 = sigma * sigma;
		int k = (int) Math.ceil(sigma * 3.0f);
		
		int width = 2 * k + 1;
		
		this.data = new double[width];
		
		for (int i = -k; i <= k; i++)
			data[i + k] = 1.0 / (Math.sqrt(2 * Math.PI) * sigma * Math.exp(((i * i) / sigma2) * 0.5f));
		
		normalize(data);
		return this;
	}
	
	/**
	 * Sets the kernel values
	 * 
	 * @param data
	 * @param isNormalized
	 *            true if the given values are already normalized to [0-1]
	 */
	public Kernels1D createCustomKernel1D(double data[], boolean isNormalized)
	{
		this.data = new double[data.length];
		System.arraycopy(data, 0, this.data, 0, data.length);
		if (!isNormalized)
			normalize(this.data);
		return this;
	}
	
	/**
	 * Normalizes the given kernel such that the values sum up to 1
	 * 
	 * @param data
	 *            the kernel data in Z-XY order
	 * @return
	 */
	private static double[] normalize(double[] data)
	{
		double accu = 0;
		
		for (double d : data)
			accu += d;
		
		if (accu != 1 && accu != 0)
		{
			for (int i = 0; i < data.length; i++)
				data[i] /= accu;
		}
		
		return data;
	}
	
}
