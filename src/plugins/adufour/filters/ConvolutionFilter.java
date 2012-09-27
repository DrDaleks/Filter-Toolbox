package plugins.adufour.filters;

import icy.image.IcyBufferedImage;
import icy.image.IcyBufferedImageUtil;
import icy.sequence.Sequence;
import icy.system.SystemUtil;
import icy.type.DataType;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import plugins.adufour.vars.lang.VarBoolean;

public class ConvolutionFilter
{
    /**
     * Filter the given sequence with the specified non-linear filter on the specified neighborhood.
     * Note that some operations require double floating-point precision, therefore the input
     * sequence will be internally converted to double precision. However the result will be
     * converted back to the same type as the given input sequence <i>with re-scaling</i>.
     * 
     * @param stopFlag
     *            a flag variable that will stop the filtering process if set to true (this flag is
     *            first set to false when starting this method)
     * @param sequence
     *            the sequence to filter (its data will be overwritten)
     * @param filterType
     *            the type of filter to apply
     * @param radius
     *            the neighborhood radius in each dimension (the actual neighborhood size will be
     *            <code>1+(2*radius)</code> to ensure it is centered on each pixel). If a single
     *            value is given, this value is used for all sequence dimensions. If two values are
     *            given for a 3D sequence, the filter is considered in 2D and applied to each Z
     *            section independently.
     */
    public void filter(VarBoolean stopFlag, Sequence sequence, final GenericFilterOperation filter, int... radius)
    {
        stopFlag.setValue(false);
        
        if (radius.length == 0) throw new IllegalArgumentException("Provide at least one filter radius");
        
        ExecutorService service = Executors.newFixedThreadPool(SystemUtil.getAvailableProcessors());
        
        final int width = sequence.getSizeX();
        final int height = sequence.getSizeY();
        final int depth = sequence.getSizeZ();
        final int channels = sequence.getSizeC();
        
        final int kWidth = radius[0];
        final int kHeight = radius.length == 1 ? kWidth : radius[1];
        final int kDepth = radius.length == 1 ? kWidth : radius.length == 2 ? 1 : radius[2];
        
        final int neighorhoodSize = 1 + kWidth * kHeight * kDepth * 8;
        
        // temporary buffers in double precision
        final double[][][] in_Z_C_XY = new double[channels][depth][width * height];
        IcyBufferedImage[] outSlices = new IcyBufferedImage[depth];
        for (int z = 0; z < depth; z++)
            outSlices[z] = new IcyBufferedImage(width, height, channels, DataType.DOUBLE);
        
        // create an array of tasks for multi-thread processing
        // => rationale: one task per image line
        ArrayList<Future<?>> tasks = new ArrayList<Future<?>>(height);
        
        convolution: for (int t = 0; t < sequence.getSizeT(); t++)
        {
            // retrieve the current time/channel in double precision
            for (int z = 0; z < depth; z++)
                in_Z_C_XY[z] = IcyBufferedImageUtil.convertToType(sequence.getImage(t, z), DataType.DOUBLE, true).getDataXYCAsDouble();
            
            for (int z = 0; z < depth; z++)
            {
                final int slice = z;
                
                for (int c = 0; c < channels; c++)
                {
                    final double[][] in_Z_XY = in_Z_C_XY[c];
                    final double[] _outXY = outSlices[z].getDataXYAsDouble(c);
                    
                    for (int y = 0; y < height; y++)
                    {
                        final int line = y;
                        
                        // clear the task array
                        tasks.clear();
                        
                        // submit a new filtering task for the current line
                        tasks.add(service.submit(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                double[] neighborhood = new double[neighorhoodSize];
                                
                                int kX, inX, kY, inY, kZ, inZ;
                                int inXY, outXY = line * width;
                                
                                // process each pixel of the current line
                                for (int x = 0; x < width; x++, outXY++)
                                {
                                    int localNeighborHoodSize = 0;
                                    
                                    double currentValue = in_Z_XY[slice][outXY];
                                    
                                    // browse the neighborhood along Z
                                    for (kZ = -kDepth; kZ <= kDepth; kZ++)
                                    {
                                        inZ = slice + kZ;
                                        
                                        // out-of-range => nothing to do
                                        if (inZ < 0 || inZ >= depth) continue;
                                        
                                        // browse the neighborhood along Y
                                        for (kY = -kHeight; kY <= kHeight; kY++)
                                        {
                                            inY = line + kY;
                                            
                                            // out-of-range => nothing to do
                                            if (inY < 0 || inY >= height) continue;
                                            
                                            // this is the line offset
                                            inXY = inY * width;
                                            
                                            // browse the neighborhood X
                                            for (kX = -kWidth; kX <= kWidth; kX++)
                                            {
                                                inX = x + kX;
                                                
                                                // out-of-range => nothing to do
                                                if (inX < 0 || inX >= width) continue;
                                                
                                                neighborhood[localNeighborHoodSize++] = in_Z_XY[inZ][inXY + inX];
                                            }
                                        }
                                    }
                                    
                                    // the neighborhood has been browsed and stored.
                                    // => the filter can be applied here
                                    
                                    double[] localNeighborHood = new double[localNeighborHoodSize];
                                    System.arraycopy(neighborhood, 0, localNeighborHood, 0, localNeighborHoodSize);
                                    _outXY[outXY] = filter.process(currentValue, localNeighborHood);
                                }
                            }
                        }));
                        
                        if (stopFlag.getValue()) break;
                        
                    } // end for(y)
                    
                    try
                    {
                        for (Future<?> f : tasks)
                            f.get();
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                    catch (ExecutionException e)
                    {
                        e.printStackTrace();
                    }
                    
                    if (stopFlag.getValue()) break convolution;
                } // end for(c)
            } // end for(z)
            
            // the current stack is processed => convert and store the result
            for (int z = 0; z < depth; z++)
                sequence.setImage(t, z, IcyBufferedImageUtil.convertToType(outSlices[z], sequence.getDataType_(), true));
            
        } // end for(t)
        
        sequence.dataChanged();
        service.shutdown();
    }
}
