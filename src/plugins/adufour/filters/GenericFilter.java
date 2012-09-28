package plugins.adufour.filters;

import icy.image.IcyBufferedImage;
import icy.image.IcyBufferedImageUtil;
import icy.sequence.Sequence;
import icy.sequence.SequenceUtil;
import icy.system.SystemUtil;
import icy.type.DataType;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import plugins.adufour.blocks.lang.Block;
import plugins.adufour.blocks.util.VarList;
import plugins.adufour.ezplug.EzPlug;
import plugins.adufour.ezplug.EzStoppable;
import plugins.adufour.ezplug.EzVarInteger;
import plugins.adufour.ezplug.EzVarPlugin;
import plugins.adufour.ezplug.EzVarSequence;
import plugins.adufour.vars.lang.Var;
import plugins.adufour.vars.lang.VarBoolean;
import plugins.adufour.vars.lang.VarDouble;
import plugins.adufour.vars.util.VarListener;

public class GenericFilter extends EzPlug implements EzStoppable, Block
{
    EzVarSequence                       input    = new EzVarSequence("input");
    
    EzVarPlugin<GenericFilterOperation> filterOp = new EzVarPlugin<GenericFilterOperation>("filter", GenericFilterOperation.class);
    
    EzVarInteger                        radius   = new EzVarInteger("radius", 1, 1, Short.MAX_VALUE, 1);
    
    VarBoolean                          stopFlag = new VarBoolean("stop", false);
    
    VarDouble                           progress = new VarDouble("Progression", 0.0);
    
    @Override
    protected void initialize()
    {
        addEzComponent(input);
        addEzComponent(filterOp);
        addEzComponent(radius);
        setTimeDisplay(true);
    }
    
    @Override
    protected void execute()
    {
        if (!isHeadLess()) progress.addListener(new VarListener<Double>()
        {
            @Override
            public void valueChanged(Var<Double> source, Double oldValue, Double newValue)
            {
                getUI().setProgressBarValue(newValue);
            }
            
            @Override
            public void referenceChanged(Var<Double> source, Var<? extends Double> oldReference, Var<? extends Double> newReference)
            {
            }
        });
        
        Sequence output = SequenceUtil.getCopy(input.getValue(true));
        filter(stopFlag, output, filterOp.newInstance(), radius.getValue());
        
        if (!isHeadLess())
        {
            addSequence(output);
            progress.removeListeners();
        }
    }
    
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
        progress.setValue(0.0);
        
        if (radius.length == 0) throw new IllegalArgumentException("Provide at least one filter radius");
        
        ExecutorService service = Executors.newFixedThreadPool(SystemUtil.getAvailableProcessors() * 2);
        
        final int width = sequence.getSizeX();
        final int height = sequence.getSizeY();
        final int depth = sequence.getSizeZ();
        final int channels = sequence.getSizeC();
        
        final double taskIncrement = 1.0 / (height * depth * channels * sequence.getSizeT());
        
        final int kWidth = radius[0];
        final int kHeight = radius.length == 1 ? kWidth : radius[1];
        final int kDepth = radius.length == 1 ? kWidth : radius.length == 2 ? 1 : radius[2];
        
        final int neighorhoodSize = (1 + kWidth * 2) * (1 + kHeight * 2) * (1 + kDepth * 2);
        
        // temporary buffers in double precision
        final double[][] in_Z_XY = new double[depth][width * height];
        IcyBufferedImage[] outSlices = new IcyBufferedImage[depth];
        for (int z = 0; z < depth; z++)
            outSlices[z] = new IcyBufferedImage(width, height, channels, DataType.DOUBLE);
        
        // create an array of tasks for multi-thread processing
        // => rationale: one task per image line
        ArrayList<Future<?>> tasks = new ArrayList<Future<?>>(height);
        
        convolution: for (int t = 0; t < sequence.getSizeT(); t++)
        {
            // retrieve the current time/channel in double precision
            for (int c = 0; c < channels; c++)
            {
                for (int z = 0; z < depth; z++)
                    in_Z_XY[z] = IcyBufferedImageUtil.convertToType(sequence.getImage(t, z, c), DataType.DOUBLE, true).getDataXYAsDouble(0);
                
                for (int z = 0; z < depth; z++)
                {
                    final int slice = z;
                    
                    final double[] _inXY = in_Z_XY[slice];
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
                                    
                                    double currentValue = _inXY[outXY];
                                    
                                    // browse the neighborhood along Z
                                    for (kZ = -kDepth; kZ <= kDepth; kZ++)
                                    {
                                        inZ = slice + kZ;
                                        
                                        // out-of-range => nothing to do
                                        if (inZ < 0 || inZ >= depth) continue;
                                        
                                        double[] inSlice = in_Z_XY[inZ];
                                        
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
                                                
                                                neighborhood[localNeighborHoodSize++] = inSlice[inXY + inX];
                                            }
                                        }
                                    }
                                    
                                    // the neighborhood has been browsed and stored.
                                    // => the filter can be applied here
                                    
                                    double[] localNeighborHood = new double[localNeighborHoodSize];
                                    System.arraycopy(neighborhood, 0, localNeighborHood, 0, localNeighborHoodSize);
                                    _outXY[outXY] = filter.process(currentValue, localNeighborHood);
                                }
                                
                                progress.setValue(progress.getValue() + taskIncrement);
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
            
            // the current stack is processed => convert and store the result
            for (int z = 0; z < depth; z++)
                sequence.setImage(t, z, IcyBufferedImageUtil.convertToType(outSlices[z], sequence.getDataType_(), true));
            
        } // end for(t)
        
        sequence.dataChanged();
        service.shutdown();
    }
        
    @Override
    public void clean()
    {
        // TODO Auto-generated method stub
        
    }
    
    @Override
    public void declareInput(VarList inputMap)
    {
        // TODO Auto-generated method stub
        
    }
    
    @Override
    public void declareOutput(VarList outputMap)
    {
        // TODO Auto-generated method stub
        
    }
    
    @Override
    public void stopExecution()
    {
        stopFlag.setValue(true);
    }
    
}
