package plugins.adufour.filtering;

import icy.image.IcyBufferedImage;
import icy.sequence.Sequence;
import icy.type.DataType;
import icy.type.collection.array.Array1DUtil;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public abstract class SelectionFilter extends Filter
{
    /**
     * Process the current image pixel
     * 
     * @param currentValue
     *            the value of the current pixel
     * @param neighborhood
     *            the neighborhood of the current pixel (inclusive). The order in which neighborhood
     *            values are given in this array is arbitrary. This array can be modified without
     *            disturbing the filtering process.
     * @param neighborHoodSize
     *            the number of elements to consider in the neighborhood argument
     * @return
     */
    abstract double process(double currentValue, double[] neighborhood, int neighborHoodSize);
    
    /**
     * Filter the given sequence with the specified non-linear filter on the specified (square)
     * neighborhood. Note that some operations require double floating-point precision, therefore
     * the input sequence will be internally converted to double precision. However the result will
     * be converted back to the same type as the given input sequence <i>with re-scaling</i>.
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
    public Sequence filterSquare(Sequence sequence, int... radius)
    {
        Sequence out = new Sequence(sequence.getName() + "_" + getDescriptor().getName());
        
        stopFlag.setValue(false);
        progress.setValue(0.0);
        
        if (radius.length == 0) throw new IllegalArgumentException("Provide at least one filter radius");
        
        final int width = sequence.getSizeX();
        final int height = sequence.getSizeY();
        final int depth = sequence.getSizeZ();
        final int channels = sequence.getSizeC();
        final DataType type = sequence.getDataType_();
        final boolean signed = sequence.isSignedDataType();
        
        final double taskIncrement = 1.0 / (height * depth * channels * sequence.getSizeT());
        
        final int kWidth = radius[0];
        final int kHeight = radius.length == 1 ? kWidth : radius[1];
        final int kDepth = radius.length == 1 ? kWidth : radius.length == 2 ? 0 : radius[2];
        
        final Object[] in_Z_XY = new Object[depth];
        
        final double[] cache = new double[width * height];
        
        // create an array of tasks for multi-thread processing
        // => rationale: one task per image line
        ArrayList<Future<?>> tasks = new ArrayList<Future<?>>(height);
        
        convolution: for (int t = 0; t < sequence.getSizeT(); t++)
        {
            for (int z = 0; z < depth; z++)
                out.setImage(t, z, new IcyBufferedImage(width, height, channels, type));
            
            for (int c = 0; c < channels; c++)
            {
                for (int z = 0; z < depth; z++)
                    in_Z_XY[z] = sequence.getImage(t, z, c).getDataXY(0);
                
                for (int z = 0; z < depth; z++)
                {
                    final int minZinclusive = Math.max(z - kDepth, 0);
                    final int maxZexclusive = Math.min(z + kDepth + 1, depth);
                    final Object _inXY = in_Z_XY[z];
                    final Object _outXY = out.getDataXY(t, z, c);
                    
                    // clear the task array
                    tasks.clear();
                    
                    for (int y = 0; y < height; y++)
                    {
                        final int line = y;
                        final int minYinclusive = Math.max(y - kHeight, 0);
                        final int maxYexclusive = Math.min(y + kHeight + 1, height);
                        final int lineOffset = y * width;
                        
                        final int maxNeighbors = (1 + (maxZexclusive - minZinclusive) * 2) * (1 + (maxYexclusive - minYinclusive) * 2) * (1 + kWidth * 2);
                        
                        // submit a new filtering task for the current line
                        tasks.add(service.submit(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                double[] neighborhood = new double[maxNeighbors];
                                
                                int inX, inY, inZ;
                                int inXY, outXY = lineOffset;
                                
                                double currentPixel;
                                
                                // process each pixel of the current line
                                for (int x = 0; x < width; x++, outXY++)
                                {
                                    currentPixel = Array1DUtil.getValue(_inXY, outXY, type);
                                    
                                    int localNeighborHoodSize = 0;
                                    int minXinclusive = Math.max(x - kWidth, 0);
                                    int maxXexclusive = Math.min(x + kWidth + 1, width);
                                    
                                    // browse the neighborhood along Z
                                    for (inZ = minZinclusive; inZ < maxZexclusive; inZ++)
                                    {
                                        Object neighborSlice = in_Z_XY[inZ];
                                        
                                        // browse the neighborhood along Y
                                        for (inY = minYinclusive; inY < maxYexclusive; inY++)
                                        {
                                            // this is the line offset
                                            inXY = inY * width + minXinclusive;
                                            
                                            // browse the neighborhood X
                                            for (inX = minXinclusive; inX < maxXexclusive; inX++, inXY++, localNeighborHoodSize++)
                                            {
                                                neighborhood[localNeighborHoodSize] = Array1DUtil.getValue(neighborSlice, inXY, type);
                                            }
                                        }
                                    }
                                    
                                    // the neighborhood has been browsed and stored.
                                    // => the filter can be applied here
                                    
                                    cache[outXY] = process(currentPixel, neighborhood, localNeighborHoodSize);
                                }
                                
                                Array1DUtil.doubleArrayToSafeArray(cache, lineOffset, _outXY, lineOffset, width, signed);
                                
                                if (line % 3 == 0) progress.setValue(progress.getValue() + taskIncrement * 3);
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
                } // end for(z)
            } // end for(c)
        } // end for(t)
        
        return out;
    }
}
