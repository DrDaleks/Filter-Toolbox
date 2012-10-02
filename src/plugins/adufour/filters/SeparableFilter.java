package plugins.adufour.filters;

import icy.image.IcyBufferedImage;
import icy.sequence.Sequence;
import icy.system.SystemUtil;
import icy.type.DataType;
import icy.type.collection.array.Array1DUtil;

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

public class SeparableFilter extends EzPlug implements EzStoppable, Block
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
        
        Sequence output = filterSquare(stopFlag, input.getValue(true), filterOp.newInstance(), radius.getValue(), radius.getValue(), input.getValue().getSizeZ() == 1 ? 0 : radius.getValue());
        
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
    public Sequence filterSquare(VarBoolean stopFlag, Sequence sequence, final GenericFilterOperation filter, int... radius)
    {
        Sequence out = new Sequence(sequence.getName() + "_" + filter.getDescriptor().getName());
        
        stopFlag.setValue(false);
        progress.setValue(0.0);
        
        if (radius.length == 0) throw new IllegalArgumentException("Provide at least one filter radius");
        
        final int nThreads = SystemUtil.getAvailableProcessors();
        
        ExecutorService service = Executors.newFixedThreadPool(nThreads);
        
        final int width = sequence.getSizeX();
        final int height = sequence.getSizeY();
        final int depth = sequence.getSizeZ();
        final int channels = sequence.getSizeC();
        final DataType type = sequence.getDataType_();
        final boolean signed = sequence.isSignedDataType();
        
        final double taskIncrement = 1.0 / (height * depth * channels * sequence.getSizeT());
        
        final int kWidth = radius[0];
        final int kHeight = radius.length == 1 ? kWidth : radius[1];
        final int kDepth = radius.length == 1 ? kWidth : radius.length == 2 ? 1 : radius[2];
        
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
                        final int lineOffset = y * width;
                        
                        // submit a new filtering task for the current line
                        tasks.add(service.submit(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                Array1DUtil.arrayToDoubleArray(_inXY, lineOffset, cache, lineOffset, width, signed);
                                
                                double[] neighborhood = new double[kWidth * 2 + 1];
                                
                                int inX;
                                int inXY, outXY = lineOffset;
                                
                                // process each pixel of the current line
                                for (int x = 0; x < width; x++, outXY++)
                                {
                                    int localNeighborHoodSize = 0;
                                    int minXinclusive = Math.max(x - kWidth, 0);
                                    int maxXexclusive = Math.min(x + kWidth + 1, width);
                                    inXY = lineOffset + minXinclusive;
                                    
                                    for (inX = minXinclusive; inX < maxXexclusive; inX++, inXY++, localNeighborHoodSize++)
                                    {
                                        double val = Array1DUtil.getValue(_inXY, inXY, type);
                                        neighborhood[localNeighborHoodSize] = val;
                                    }
                                    
                                    // the neighborhood has been browsed and stored.
                                    // => the filter can be applied here
                                    
                                    cache[outXY] = filter.process(cache[outXY], neighborhood, localNeighborHoodSize);
                                }
                                
                                Array1DUtil.doubleArrayToArray(cache, lineOffset, _outXY, lineOffset, width);
                                
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
        
        service.shutdown();
        return out;
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
