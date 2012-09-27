package plugins.adufour.filtering;

import icy.image.IcyBufferedImage;
import icy.sequence.Sequence;
import icy.sequence.SequenceUtil;
import icy.type.DataType;

public enum Kernels2D implements IKernel
{
    /** 2D 3x3 Laplace kernel */
    LAPLACE(3, 3, new double[] { 0, 1, 0, 1, -4, 1, 0, 1, 0 }),
    
    /** A 3x3 horizontal Prewitt kernel */
    PREWITT_X(3, 3, new double[] { 1, 1, 1, 0, 0, 0, -1, -1, -1 }),
    
    /** A 3x3 vertical Prewitt kernel */
    PREWITT_Y(3, 3, new double[] { -1, 0, 1, -1, 0, 1, -1, 0, 1 }),
    
    /** A 3x3 horizontal Sobel kernel */
    SOBEL_X(3, 3, new double[] { 1, 2, 1, 0, 0, 0, -1, -2, -1 }),
    
    /** A 3x3 vertical Sobel kernel */
    SOBEL_Y(3, 3, new double[] { -1, 0, 1, -2, 0, 2, -1, 0, 1 }),
    
    /** A 3x3 oriented north Kirsh kernel */
    KIRSCH_NORTH(3, 3, new double[] { 5, 5, 5, -3, 0, -3, -3, -3, -3 }),
    
    /** A 3x3 oriented north-east Kirsh kernel */
    KIRSCH_NORTHEAST(3, 3, new double[] { -3, 5, 5, -3, 0, 5, -3, -3, -3 }),
    
    /** A 3x3 oriented east Kirsh kernel */
    KIRSCH_EAST(3, 3, new double[] { -3, -3, 5, -3, 0, 5, -3, -3, 5 }),
    
    /** A 3x3 oriented south-east Kirsh kernel */
    KIRSCH_SOUTHEAST(3, 3, new double[] { -3, -3, -3, -3, 0, 5, -3, 5, 5 }),
    
    /** A 3x3 oriented south Kirsh kernel */
    KIRSCH_SOUTH(3, 3, new double[] { -3, -3, -3, -3, 0, -3, 5, 5, 5 }),
    
    /** A 3x3 oriented south-west Kirsh kernel */
    KIRSCH_SOUTHWEST(3, 3, new double[] { -3, -3, -3, 5, 0, -3, 5, 5, -3 }),
    
    /** A 3x3 oriented west Kirsh kernel */
    KIRSCH_WEST(3, 3, new double[] { 5, -3, -3, 5, 0, -3, 5, -3, -3 }),
    
    /** A 3x3 oriented north-west Kirsh kernel */
    KIRSCH_NORTHWEST(3, 3, new double[] { 5, 5, -3, 5, 0, -3, -3, -3, -3 }),
    
    /**
     * Custom GABOR kernel. The "GABOR" suffix is used only by the graphical user interface. To fill
     * this kernel with a GABOR function, call the
     * {@link #createGaborKernel(double, double, double, boolean)} method on this empty kernel
     */
    CUSTOM_GABOR(0, 0, null),
    
    /**
     * Custom kernel for user-defined values. Must be used in conjunction with the
     * {@link #createCustomKernel2D(double[][], boolean)} method
     */
    CUSTOM(0, 0, null),
    
    /**
     * Custom kernel that takes values from a sequence. The kernel values must be set using the
     */
    CUSTOM_SEQUENCE(0, 0, null);
    
    private int      width;
    
    private int      height;
    
    private double[] data;
    
    Kernels2D(int width, int height, double[] data)
    {
        this.width = width;
        this.height = height;
        if (data != null) this.data = Kernels2D.normalize(data);
    }
    
    /**
     * Creates an isotropic 2D Gabor kernel
     * 
     * @param sigma
     *            Gaussian std (final kernel diameter is 2 * (3*sigma) + 1)
     * @param k_x
     *            gabor radius along X
     * @param k_y
     *            gabor radius along Y
     * @param isSymmetric
     *            true if the values are symmetric (cosine), anti-symmetric (sine) otherwise
     * @return
     */
    public Kernels2D createGaborKernel2D(double sigma, double k_x, double k_y, boolean isSymmetric)
    {
        int k = (int) Math.floor(sigma * 3.0);
        
        this.width = 2 * k + 1;
        this.height = 2 * k + 1;
        this.data = new double[width * height];
        
        if (isSymmetric)
        {
            for (int i = -k; i <= k; i++)
                for (int j = -k; j <= k; j++)
                    data[(i + k) + (j + k) * width] = (double) (Math.cos(k_x * i + k_y * j) * Math.exp(-0.5f * (i * i + j * j) / (sigma * sigma)));
        }
        else
        {
            for (int i = -k; i <= k; i++)
                for (int j = -k; j <= k; j++)
                    data[(i + k) + (j + k) * width] = (double) (Math.sin(k_x * i + k_y * j) * Math.exp(-0.5f * (i * i + j * j) / (sigma * sigma)));
        }
        
        normalize(data);
        
        return this;
    }
    
    /**
     * Sets the kernel values manually
     * 
     * @param kernel
     * @param isNormalized
     *            true if the given values are already normalized to [0-1]
     */
    public Kernels2D createCustomKernel2D(double[][] kernel, boolean isNormalized)
    {
        this.width = kernel.length;
        this.height = kernel[0].length;
        this.data = new double[width * height];
        int offset = 0;
        for (double[] line : kernel)
        {
            System.arraycopy(line, 0, this.data, offset, line.length);
            offset += line.length;
        }
        if (!isNormalized) normalize(this.data);
        return this;
    }
    
    /**
     * Sets the kernel values manually
     * 
     * @param kernel
     * @param isNormalized
     *            true if the given values are already normalized to [0-1]
     */
    public Kernels2D createCustomKernel2D(double[] kernel, int width, int height, boolean isNormalized)
    {
        this.width = width;
        this.height = height;
        this.data = new double[width * height];
        System.arraycopy(kernel, 0, this.data, 0, kernel.length);
        if (!isNormalized) normalize(this.data);
        return this;
    }
    
    /**
     * Sets the kernel values using the given sequence
     * 
     * @param kernel2D
     *            the sequence to take values from
     * @param t
     *            the time point to take values from
     * @param z
     *            the slice to take values from
     * @param c
     *            the channel to take values from
     * @param isNormalized
     *            true if the given values are already normalized to [0-1]
     */
    public Kernels2D createCustomKernel2D(Sequence kernel2D, int t, int z, int c)
    {
        this.width = kernel2D.getSizeX();
        this.height = kernel2D.getSizeY();
        if (width % 2 == 0 || height % 2 == 0) throw new IllegalArgumentException("Kernel sequence must have odd dimensions");
        this.data = SequenceUtil.convertToType(kernel2D, DataType.DOUBLE, false).getDataXYAsDouble(t, z, c);
        normalize(data);
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
        double sum = 0;
        
        for (double d : data)
            sum += d;
        
        if (sum != 1 && sum != 0)
        {
            for (int i = 0; i < data.length; i++)
                data[i] /= sum;
        }
        
        return data;
    }
    
    @Override
    public Sequence toSequence()
    {
        IcyBufferedImage kernelImage = new IcyBufferedImage(width, height, 1, DataType.DOUBLE);
        kernelImage.setDataXYAsDouble(0, data);
        Sequence kernel = new Sequence(kernelImage);
        kernel.setName(this.toString());
        return kernel;
    }
    
    public double[] getData()
    {
        return data;
    }
    
}
