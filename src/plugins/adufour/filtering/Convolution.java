package plugins.adufour.filtering;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import icy.image.IcyBufferedImage;
import icy.sequence.Sequence;
import icy.type.DataType;
import icy.type.collection.array.Array1DUtil;
import plugins.adufour.vars.lang.Var;
import plugins.adufour.vars.lang.VarBoolean;
import plugins.adufour.vars.util.VarListener;

public class Convolution extends Filter
{
    /**
     * Convolve the input sequence with the given kernel with the specified edge condition. The
     * input data is overwritten with the result
     * 
     * @param input
     *            the input sequence
     * @param kernel
     *            the convolution kernel (1D, 2D or 3D), assumed to be already normalized. If the
     *            kernel has more than one channel or time point, only the first c,t are used for
     *            convolution
     * @param zeroEdges
     *            true if data outside the sequence should be treated as zero, or false for
     *            mirroring condition
     * @param nbIter
     *            the number of filter iterations
     */
    public static void convolve(Sequence input, Sequence kernel, boolean zeroEdges, int nbIter, VarBoolean stopFlag)
    {
        final Convolution c = new Convolution();
        final VarListener<Boolean> l = new VarListener<Boolean>()
        {
            @Override
            public void valueChanged(Var<Boolean> source, Boolean oldValue, Boolean newValue)
            {
                c.stopFlag.setValue(newValue);
            }
            
            @Override
            public void referenceChanged(Var<Boolean> source, Var<? extends Boolean> oldReference, Var<? extends Boolean> newReference)
            {
                
            }
        };
        stopFlag.addListener(l);
        c.convolve(input, kernel, zeroEdges, nbIter);
        stopFlag.removeListener(l);
        c.service.shutdown();
    }
    
    public Sequence convolve(final Sequence sequence, Sequence kernel, final boolean zeroEdges, int nbIter)
    {
        Sequence out = new Sequence(sequence.getName() + "_" + getDescriptor().getName());
        
        final int width = sequence.getSizeX();
        final int height = sequence.getSizeY();
        final int depth = sequence.getSizeZ();
        final int channels = sequence.getSizeC();
        final int frames = sequence.getSizeT();
        final DataType type = sequence.getDataType_();
        final boolean signed = type.isSigned();
        
        final double taskIncrement = 1.0 / (height * depth * channels * sequence.getSizeT());
        
        final int kWidth = kernel.getSizeX() >> 1;
        final int kHeight = kernel.getSizeY() >> 1;
        final int kDepth = kernel.getSizeZ() >> 1;
        
        final Object[] in_Z_XY = new Object[depth];
        
        final double[] cache = new double[width * height];
        
        final double[][] _kernel = kernel.getDataXYZAsDouble(0, 0);
        
        // create an array of tasks for multi-thread processing
        // => rationale: one task per image line
        ArrayList<Future<?>> tasks = new ArrayList<Future<?>>(height);
        
        convolution: for (int t = 0; t < frames; t++)
        {
            for (int z = 0; z < depth; z++)
                out.setImage(t, z, new IcyBufferedImage(width, height, channels, type));
            
            for (int c = 0; c < channels; c++)
            {
                // retrieve the input data in double format for convolution
                
                for (int z = 0; z < depth; z++)
                    in_Z_XY[z] = sequence.getImage(t, z, c).getDataXY(0);
                
                for (int i = 0; i < nbIter; i++)
                {
                    for (int z = 0; z < depth; z++)
                    {
                        final int slice = z;
                        final Object out_XY = out.getDataXY(t, z, c);
                        
                        // clear the task array
                        tasks.clear();
                        
                        for (int y = 0; y < height; y++)
                        {
                            final int line = y;
                            final int lineOffset = y * width;
                            
                            // submit a new filtering task for the current line
                            tasks.add(service.submit(new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    int kX, kY, kZ;
                                    int inX, inY, inZ;
                                    int kXY, inXY, outXY = lineOffset;
                                    
                                    for (int x = 0; x < width; x++, outXY++)
                                    {
                                        // core convolution code
                                        
                                        double conv = 0;
                                        
                                        // sweep through the kernel along Z
                                        for (kZ = -kDepth; kZ <= kDepth; kZ++)
                                        {
                                            inZ = slice + kZ;
                                            
                                            // mirror boundary condition
                                            if (inZ < 0)
                                            {
                                                if (zeroEdges) continue;
                                                
                                                inZ = -inZ + 1;
                                            }
                                            else if (inZ >= depth)
                                            {
                                                if (zeroEdges) continue;
                                                
                                                inZ = (depth * 2) - inZ - 1;
                                            }
                                            
                                            Object in_XY = in_Z_XY[inZ];
                                            double[] k_XY = _kernel[kZ];
                                            
                                            kXY = 0;
                                            
                                            // sweep through the kernel along Y
                                            for (kY = -kHeight; kY <= kHeight; kY++)
                                            {
                                                inY = line + kY;
                                                
                                                // mirror boundary condition
                                                if (inY < 0)
                                                {
                                                    if (zeroEdges) continue;
                                                    
                                                    inY = -inY + 1;
                                                }
                                                else if (inY >= height)
                                                {
                                                    if (zeroEdges) continue;
                                                    
                                                    inY = (height * 2) - inY - 1;
                                                }
                                                
                                                // this is the line offset
                                                inXY = inY * width;
                                                
                                                // sweep through the kernel along X
                                                for (kX = -kWidth; kX <= kWidth; kX++, kXY++)
                                                {
                                                    inX = x + kX;
                                                    
                                                    // mirror boundary condition
                                                    if (inX < 0)
                                                    {
                                                        if (zeroEdges) continue;
                                                        
                                                        inX = -inX + 1;
                                                    }
                                                    else if (inX >= width)
                                                    {
                                                        if (zeroEdges) continue;
                                                        
                                                        inX = (width * 2) - inX - 1;
                                                    }
                                                    
                                                    // Enough of this crap ! convolve god damn it !!
                                                    
                                                    conv += Array1DUtil.getValue(in_XY, inXY + inX, type) * k_XY[kXY];
                                                }
                                            }
                                        }
                                        // store the result in the temporary buffer
                                        cache[outXY] = conv;
                                    } // end for(x)
                                    
                                    Array1DUtil.doubleArrayToSafeArray(cache, lineOffset, out_XY, lineOffset, width, signed);
                                    
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
                    if (stopFlag.getValue()) break convolution;
                } // end for(i)
            } // end for(c)
        } // end for(t)
        
        return out;
    }
}
