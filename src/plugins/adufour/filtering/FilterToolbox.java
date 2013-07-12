package plugins.adufour.filtering;

import icy.image.IcyBufferedImage;
import icy.sequence.DimensionId;
import icy.sequence.Sequence;
import icy.sequence.SequenceUtil;
import icy.system.IcyHandledException;

import java.io.IOException;
import java.util.ArrayList;

import plugins.adufour.ezplug.EzException;
import plugins.adufour.ezplug.EzGroup;
import plugins.adufour.ezplug.EzPlug;
import plugins.adufour.ezplug.EzStoppable;
import plugins.adufour.ezplug.EzVar;
import plugins.adufour.ezplug.EzVarBoolean;
import plugins.adufour.ezplug.EzVarDimensionPicker;
import plugins.adufour.ezplug.EzVarDouble;
import plugins.adufour.ezplug.EzVarDoubleArrayNative;
import plugins.adufour.ezplug.EzVarEnum;
import plugins.adufour.ezplug.EzVarInteger;
import plugins.adufour.ezplug.EzVarListener;
import plugins.adufour.ezplug.EzVarPlugin;
import plugins.adufour.ezplug.EzVarSequence;
import plugins.adufour.vars.lang.Var;
import plugins.adufour.vars.lang.VarBoolean;
import plugins.adufour.vars.util.VarListener;

import com.nativelibs4java.opencl.CLBuildException;
import com.nativelibs4java.opencl.CLContext;
import com.nativelibs4java.opencl.CLException;
import com.nativelibs4java.opencl.CLProgram;
import com.nativelibs4java.opencl.CLQueue;
import com.nativelibs4java.opencl.JavaCL;
import com.ochafik.io.ReadText;

public class FilterToolbox extends EzPlug implements EzStoppable
{
    private final static int MAX_KERNEL_SIZE = 99;
    
    public enum Axis
    {
        X, Y, Z
    }
    
    public enum FilterType
    {
        CLASSIC, SEPARABLE, SELECTION
    }
    
    public EzVarSequence                     input            = new EzVarSequence("input");
    public EzVarEnum<FilterType>             filterType       = new EzVarEnum<FilterToolbox.FilterType>("Filter type", FilterType.values());
    
    public EzVarEnum<Kernels1D>              kernel1D         = new EzVarEnum<Kernels1D>("1D Kernels", Kernels1D.values());
    public EzVarBoolean                      linearSeparable  = new EzVarBoolean("Separable", true);
    public EzVarBoolean                      linearX          = new EzVarBoolean("Along X", true);
    public EzVarBoolean                      linearY          = new EzVarBoolean("Along Y", true);
    public EzVarBoolean                      linearZ          = new EzVarBoolean("Along Z", true);
    
    public EzVarDouble                       gaussianX        = new EzVarDouble("Sigma X", 1, 0, 100, 0.1);
    public EzVarDouble                       gaussianY        = new EzVarDouble("Sigma Y", 1, 0, 100, 0.1);
    public EzVarDouble                       gaussianZ        = new EzVarDouble("Sigma Z", 1, 0, 100, 0.1);
    
    public EzVarEnum<Kernels2D>              kernel2D         = new EzVarEnum<Kernels2D>("2D Kernels", Kernels2D.values());
    
    public EzVarDouble                       gaborSigma       = new EzVarDouble("Sigma", 0, 100, 0.1);
    public EzVarDouble                       gaborKx          = new EzVarDouble("Kx", 0, 100, 0.1);
    public EzVarDouble                       gaborKy          = new EzVarDouble("Ky", 0, 100, 0.1);
    public EzVarBoolean                      gaborSymmetric   = new EzVarBoolean("Symmertric", true);
    
    public EzVarPlugin<SelectionFilter>      selectionFilter  = new EzVarPlugin<SelectionFilter>("selection filter", SelectionFilter.class);
    public EzVarInteger                      selectionRadiusX = new EzVarInteger("Filter radius (X)", 1, 0, MAX_KERNEL_SIZE, 1);
    public EzVarInteger                      selectionRadiusY = new EzVarInteger("Filter radius (Y)", 1, 0, MAX_KERNEL_SIZE, 1);
    public EzVarInteger                      selectionRadiusZ = new EzVarInteger("Filter radius (Z)", 1, 0, MAX_KERNEL_SIZE, 1);
    
    public EzVarBoolean                      zeroEdge         = new EzVarBoolean("Zero on edge", false);
    
    public EzVarInteger                      userKernelWidth  = new EzVarInteger("kernel width", 3, MAX_KERNEL_SIZE, 2);
    public EzVarInteger                      userKernelHeight = new EzVarInteger("kernel height", 1, MAX_KERNEL_SIZE, 2);
    public ArrayList<EzVarDoubleArrayNative> kernelLines      = new ArrayList<EzVarDoubleArrayNative>(1);
    
    public EzVarSequence                     customKernel     = new EzVarSequence("Kernel sequence");
    public EzVarDimensionPicker              customKernel_T   = new EzVarDimensionPicker("T", DimensionId.T, customKernel.getVariable());
    public EzVarDimensionPicker              customKernel_Z   = new EzVarDimensionPicker("Z", DimensionId.Z, customKernel.getVariable());
    public EzVarDimensionPicker              customKernel_C   = new EzVarDimensionPicker("C", DimensionId.C, customKernel.getVariable());
    
    public EzVarInteger                      iterations       = new EzVarInteger("nb. iterations", 1, 1, 10000, 1);
    
    private ConvolutionCL                    convolutionCL;
    private CLContext                        context;
    private CLQueue                          queue;
    private CLProgram                        program;
    
    public EzVarBoolean                      useOpenCL        = new EzVarBoolean("Use OpenCL", false);
    
    private VarBoolean                       stopFlag         = new VarBoolean("stop", false);
    
    private Filter                           filter;
    
    @Override
    public void initialize()
    {
        addEzComponent(input);
        
        input.addVarChangeListener(new EzVarListener<Sequence>()
        {
            @Override
            public void variableChanged(EzVar<Sequence> source, Sequence newValue)
            {
                if (newValue == null) return;
                
                boolean is3D = newValue.getSizeZ() > 1;
                
                linearZ.setVisible(is3D);
                gaussianZ.setVisible(is3D);
                selectionRadiusZ.setVisible(is3D);
            }
        });
        input.valueChanged(input.getVariable(), null, input.getValue());
        
        addEzComponent(filterType);

        addEzComponent(iterations);
        
        try
        {
            useOpenCL.addVarChangeListener(new EzVarListener<Boolean>()
            {
                @Override
                public void variableChanged(EzVar<Boolean> source, Boolean newValue)
                {
                    if (newValue)
                    {
                        linearZ.setVisible(false);
                        gaussianZ.setVisible(false);
                    }
                }
            });
            
            // The OpenCL option should now be a default (implementation switches to CPU when necessary)
            //addEzComponent(useOpenCL);
            useOpenCL.setVisible(false);
            context = JavaCL.createBestContext();
            queue = context.createDefaultQueue();
            String programFile = ReadText.readText(ConvolutionCL.class.getResourceAsStream("Convolution.cl"));
            program = context.createProgram(programFile).build();
            convolutionCL = new ConvolutionCL(context, program, queue);
            useOpenCL.setValue(true);
            useOpenCL.setVisible(true);
        }
        catch (IOException e)
        {
            System.out.println("Warning (FilterToolbox): unable to load the OpenCL code. Continuing in pure Java mode.");
            e.printStackTrace();
        }
        catch (CLException e)
        {
            System.out.println("Warning (FilterToolbox): unable to create the OpenCL context. Continuing in pure Java mode.");
            e.printStackTrace();
        }
        catch (CLBuildException e)
        {
            System.out.println("Warning (FilterToolbox): unable to create the OpenCL context. Continuing in pure Java mode.");
            e.printStackTrace();
        }
        catch (NoClassDefFoundError e)
        {
            System.out.println("Warning (FilterToolbox): unable to create the OpenCL context. Continuing in pure Java mode.");
            e.printStackTrace();
        }
        catch (UnsatisfiedLinkError linkError)
        {
            // throw new EzException("Unable to load OpenCL drivers on this system", true);
            System.out.println("Warning (FilterToolbox): OpenCL drivers not found. Using basic Java implementation.");
        }
        
        filterType.addVisibilityTriggerTo(useOpenCL, FilterType.CLASSIC, FilterType.SEPARABLE);
        filterType.addVisibilityTriggerTo(iterations, FilterType.CLASSIC, FilterType.SEPARABLE);
        
        addEzComponent(kernel1D);
        filterType.addVisibilityTriggerTo(kernel1D, FilterType.SEPARABLE);
        
        EzGroup groupLinear = new EzGroup("Directions", linearX, linearY, linearZ, linearSeparable);
        addEzComponent(groupLinear);
        filterType.addVisibilityTriggerTo(groupLinear, FilterType.SEPARABLE);
        kernel1D.addVisibilityTriggerTo(linearSeparable, Kernels1D.CUSTOM);
        useOpenCL.addVisibilityTriggerTo(linearZ, false);
        
        EzGroup groupGaussian = new EzGroup("Gaussian filter", gaussianX, gaussianY, gaussianZ);
        addEzComponent(groupGaussian);
        kernel1D.addVisibilityTriggerTo(groupGaussian, Kernels1D.CUSTOM_GAUSSIAN);
        useOpenCL.addVisibilityTriggerTo(gaussianZ, false);
        
        addEzComponent(kernel2D);
        filterType.addVisibilityTriggerTo(kernel2D, FilterType.CLASSIC);
        
        EzGroup gaborGroup = new EzGroup("Gabor 2D", gaborSigma, gaborKx, gaborKy, gaborSymmetric);
        addEzComponent(gaborGroup);
        kernel2D.addVisibilityTriggerTo(gaborGroup, Kernels2D.CUSTOM_GABOR);
        
        EzGroup customSequenceGroup = new EzGroup("Custom sequence", customKernel, customKernel_T, customKernel_Z, customKernel_C);
        addEzComponent(customSequenceGroup);
        kernel2D.addVisibilityTriggerTo(customSequenceGroup, Kernels2D.CUSTOM_SEQUENCE);
        
        EzGroup groupSelection = new EzGroup("Selection filter", selectionFilter, selectionRadiusX, selectionRadiusY, selectionRadiusZ);
        addEzComponent(groupSelection);
        filterType.addVisibilityTriggerTo(groupSelection, FilterType.SELECTION);
        
        addEzComponent(zeroEdge);
        filterType.addVisibilityTriggerTo(zeroEdge, FilterType.CLASSIC, FilterType.SEPARABLE);
        
        addEzComponent(userKernelWidth);
        kernel1D.addVisibilityTriggerTo(userKernelWidth, Kernels1D.CUSTOM);
        kernel2D.addVisibilityTriggerTo(userKernelWidth, Kernels2D.CUSTOM);
        
        addEzComponent(userKernelHeight);
        kernel2D.addVisibilityTriggerTo(userKernelHeight, Kernels2D.CUSTOM);
        
        // first line of the kernel must always exist
        EzVarDoubleArrayNative firstKernelLine = new EzVarDoubleArrayNative("Line 1", new double[][] { new double[] { 1f, 1f, 1f } }, 0, true);
        kernelLines.add(firstKernelLine);
        addEzComponent(firstKernelLine);
        kernel1D.addVisibilityTriggerTo(firstKernelLine, Kernels1D.CUSTOM);
        kernel2D.addVisibilityTriggerTo(firstKernelLine, Kernels2D.CUSTOM);
        
        // other lines
        userKernelHeight.addVarChangeListener(new EzVarListener<Integer>()
        {
            @Override
            public void variableChanged(EzVar<Integer> source, Integer newValue)
            {
                if (kernelLines.size() < newValue)
                {
                    int nbLines = kernelLines.size();
                    int nbNewLines = newValue - nbLines;
                    for (int i = 1; i <= nbNewLines; i++)
                    {
                        int lineNumber = i + nbLines;
                        EzVarDoubleArrayNative newLine = new EzVarDoubleArrayNative("Line " + lineNumber, new double[][] { new double[] { 1f, 1f, 1f } }, 0, true);
                        kernelLines.add(newLine);
                        kernel2D.addVisibilityTriggerTo(newLine, Kernels2D.CUSTOM);
                        addEzComponent(newLine);
                    }
                }
                else
                {
                    if (userKernelHeight.isVisible()) for (int i = 0; i < kernelLines.size(); i++)
                        kernelLines.get(i).setVisible(i < newValue);
                }
                if (getUI() != null) getUI().repack(true);
            }
        });
        
        setTimeDisplay(true);
    }
    
    @Override
    public void execute()
    {
        stopFlag.setValue(false);
        
        Sequence inSeq = input.getValue(true);
        try
        {
            switch (filterType.getValue())
            {
                case SEPARABLE:
                {
                    executeSeparable(inSeq);
                    break;
                }
                case CLASSIC:
                {
                    executeClassic(inSeq);
                    break;
                }
                case SELECTION:
                {
                    try
                    {
                        executeSelectionFilter(inSeq);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                    break;
                }
            }
        }
        catch (CLException.OutOfHostMemory o)
        {
            throw new IcyHandledException("Not enough memory to perform the filtering on the GPU. Disable OpenCL and retry.");
        }
    }
    
    private void executeSelectionFilter(Sequence inSeq) throws InstantiationException, IllegalAccessException
    {
        SelectionFilter filter = (SelectionFilter) selectionFilter.getValue().getPluginClass().newInstance();
        
        this.filter = filter;
        
        filter.progress.addListener(new VarListener<Double>()
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
        
        Sequence out = inSeq;
        
        for (int i = 0; i < iterations.getValue(); i++)
        {
            out = filter.filterSquare(out, selectionRadiusX.getValue(), selectionRadiusY.getValue(), selectionRadiusZ.getValue());
            if (stopFlag.getValue()) break;
        }
        addSequence(out);
        
        filter.progress.removeListeners();
        this.filter = null;
    }
    
    private void executeClassic(Sequence inSeq)
    {
        Kernels2D k2d = kernel2D.getValue();
        
        switch (kernel2D.getValue())
        {
            case CUSTOM_GABOR:
                k2d.createGaborKernel2D(gaborSigma.getValue(), gaborKx.getValue(), gaborKy.getValue(), gaborSymmetric.getValue());
            break;
            
            case CUSTOM:
            {
                ArrayList<Double> kernel1D = new ArrayList<Double>();
                for (int i = 0; i < userKernelHeight.getValue(); i++)
                    for (double d : kernelLines.get(i).getValue())
                        kernel1D.add(d);
                
                double[] values = new double[kernel1D.size()];
                for (int i = 0; i < kernel1D.size(); i++)
                    values[i] = kernel1D.get(i);
                
                k2d.createCustomKernel2D(values, userKernelWidth.getValue(), userKernelHeight.getValue(), false);
            }
            break;
            
            case CUSTOM_SEQUENCE:
            {
                k2d.createCustomKernel2D(customKernel.getValue(true), customKernel_T.getValue(), customKernel_Z.getValue(), customKernel_C.getValue());
            }
            break;
        }
        
        Sequence kernel = k2d.toSequence();
        Sequence output = null;
        
        boolean openCL_failed = false;
        
        if (useOpenCL.getValue())
        {
            try
            {
                output = SequenceUtil.getCopy(inSeq);
                convolutionCL.convolve(output, kernel, zeroEdge.getValue(), iterations.getValue(), stopFlag);
                openCL_failed = false;
            }
            catch (Exception e)
            {
                openCL_failed = true;
                e.printStackTrace();
                System.err.println("WARNING: Unable to run in OpenCL mode. Switching to CPU mode.");
            }
        }
        
        if (!useOpenCL.getValue() || openCL_failed)
        {
            final Convolution c = new Convolution();
            this.filter = c;
            final VarListener<Double> ld = new VarListener<Double>()
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
            };
            c.progress.addListener(ld);
            output = c.convolve(input.getValue(true), kernel, zeroEdge.getValue(), iterations.getValue());
            c.progress.removeListener(ld);
            c.service.shutdown();
            this.filter = null;
        }
        
        output.setName(inSeq.getName() + " * " + kernel.getName());
        output.updateChannelsBounds(true);
        addSequence(output);
    }
    
    private void executeSeparable(Sequence inSeq)
    {
        Kernels1D k1d = kernel1D.getValue();
        
        Sequence kernelX = null, kernelY = null, kernelZ = null;
        
        int nbDirections = 0;
        
        switch (k1d)
        {
            case CUSTOM_GAUSSIAN:
            {
                linearSeparable.setValue(true);
                
                if (linearX.getValue() && gaussianX.getValue() > 1e-10)
                {
                    kernelX = k1d.createGaussianKernel1D(gaussianX.getValue()).toSequence();
                    nbDirections++;
                }
                if (linearY.getValue() && gaussianY.getValue() > 1e-10)
                {
                    kernelY = k1d.createGaussianKernel1D(gaussianY.getValue()).toSequence();
                    nbDirections++;
                }
                if (linearZ.getValue() && gaussianZ.getValue() > 1e-10)
                {
                    kernelZ = k1d.createGaussianKernel1D(gaussianZ.getValue()).toSequence();
                    nbDirections++;
                }
            }
            break;
            
            case GRADIENT:
            {
                linearSeparable.setValue(false);
                
                Sequence kernel = k1d.toSequence();
                
                if (linearX.getValue())
                {
                    kernelX = kernel;
                    nbDirections++;
                }
                if (linearY.getValue())
                {
                    kernelY = kernel;
                    nbDirections++;
                }
                if (linearZ.getValue())
                {
                    kernelZ = kernel;
                    nbDirections++;
                }
            }
            break;
            
            case CUSTOM:
            {
                Sequence kernel = k1d.createCustomKernel1D(kernelLines.get(0).getValue(), false).toSequence();
                
                if (linearX.getValue())
                {
                    kernelX = kernel;
                    nbDirections++;
                }
                if (linearY.getValue())
                {
                    kernelY = kernel;
                    nbDirections++;
                }
                if (linearZ.getValue())
                {
                    kernelZ = kernel;
                    nbDirections++;
                }
            }
            break;
        }
        
        if (nbDirections == 0) throw new IcyHandledException("Filter toolbox: no direction selected");
        
        boolean openCL_failed = false;
        
        if (useOpenCL.getValue())
        {
            if (linearZ.getValue())
            {
                System.out.println("Warning: unable to use OpenCL for a convolution along the Z axis, continuing in CPU mode");
                openCL_failed = true;
            }
            else try
            {
                if (linearSeparable.getValue())
                {
                    // convolve the same data along all required directions sequentially
                    Sequence output = SequenceUtil.getCopy(inSeq);
                    String newName = output.getName() + " * " + k1d.name() + " (";
                    
                    if (linearX.getValue())
                    {
                        convolutionCL.convolve(output, kernelX, zeroEdge.getValue(), iterations.getValue(), stopFlag);
                        newName += 'X';
                    }
                    if (linearY.getValue())
                    {
                        // in OpenCL mode, the kernel along Y has to be vertical
                        Sequence kernelY_vertical = new Sequence(new IcyBufferedImage(1, kernelY.getSizeX(), 1, kernelY.getDataType_()));
                        System.arraycopy(kernelY.getDataXY(0, 0, 0), 0, kernelY_vertical.getDataXY(0, 0, 0), 0, kernelY.getSizeX());
                        convolutionCL.convolve(output, kernelY_vertical, zeroEdge.getValue(), iterations.getValue(), stopFlag);
                        newName += 'Y';
                    }
                    // TODO Z
                    
                    output.setName(newName + ")");
                    output.dataChanged();
                    addSequence(output);
                }
                else
                {
                    // convolve along each direction in separate sequences
                    if (linearX.getValue())
                    {
                        Sequence output = SequenceUtil.getCopy(inSeq);
                        convolutionCL.convolve(output, kernelX, zeroEdge.getValue(), iterations.getValue(), stopFlag);
                        output.setName(output.getName() + " * " + k1d.name() + " (X)");
                        output.dataChanged();
                        addSequence(output);
                    }
                    if (linearY.getValue())
                    {
                        Sequence output = SequenceUtil.getCopy(inSeq);
                        // in OpenCL mode, the kernel along Y has to be vertical
                        Sequence kernelY_vertical = new Sequence(new IcyBufferedImage(1, kernelY.getSizeX(), 1, kernelY.getDataType_()));
                        System.arraycopy(kernelY.getDataXY(0, 0, 0), 0, kernelY_vertical.getDataXY(0, 0, 0), 0, kernelY.getSizeX());
                        convolutionCL.convolve(output, kernelY_vertical, zeroEdge.getValue(), iterations.getValue(), stopFlag);
                        output.setName(output.getName() + " * " + k1d.name() + " (Y)");
                        output.dataChanged();
                        addSequence(output);
                    }
                    // TODO Z
                }
            }
            catch (Exception e)
            {
                openCL_failed = true;
                e.printStackTrace();
                System.err.println("WARNING: Unable to run in OpenCL mode. Continuing in CPU mode.");
            }
        }
        
        if (!useOpenCL.getValue() || openCL_failed)
        {
            if (linearSeparable.getValue())
            {
                try
                {
                    Sequence output = SequenceUtil.getCopy(inSeq);
                    Convolution1D.convolve(output, kernelX, kernelY, kernelZ, iterations.getValue(), stopFlag);
                    String newName = output.getName() + " * " + k1d.name() + " (";
                    if (kernelX != null) newName += 'X';
                    if (kernelY != null) newName += 'Y';
                    if (kernelZ != null) newName += 'Z';
                    output.setName(newName + ')');
                    output.dataChanged();
                    addSequence(output);
                }
                catch (ConvolutionException e)
                {
                    throw new EzException(e.getMessage(), true);
                }
            }
            else
            {
                if (linearX.getValue()) try
                {
                    Sequence output = SequenceUtil.getCopy(inSeq);
                    Convolution1D.convolve(output, kernelX, null, null, iterations.getValue(), stopFlag);
                    output.setName(output.getName() + " * " + k1d.name() + " (X)");
                    output.dataChanged();
                    addSequence(output);
                }
                catch (ConvolutionException e)
                {
                    throw new EzException(e.getMessage(), true);
                }
                if (linearX.getValue()) try
                {
                    Sequence output = SequenceUtil.getCopy(inSeq);
                    Convolution1D.convolve(output, null, kernelY, null, iterations.getValue(), stopFlag);
                    output.setName(output.getName() + " * " + k1d.name() + " (Y)");
                    output.dataChanged();
                    addSequence(output);
                }
                catch (ConvolutionException e)
                {
                    throw new EzException(e.getMessage(), true);
                }
                if (linearX.getValue()) try
                {
                    Sequence output = SequenceUtil.getCopy(inSeq);
                    Convolution1D.convolve(output, null, null, kernelZ, iterations.getValue(), stopFlag);
                    output.setName(output.getName() + " * " + k1d.name() + " (Z)");
                    output.dataChanged();
                    addSequence(output);
                }
                catch (ConvolutionException e)
                {
                    throw new EzException(e.getMessage(), true);
                }
            }
        }
    }
    
    @Override
    public void clean()
    {
        if (useOpenCL.getValue())
        {
            if (queue != null) queue.release();
            if (context != null) context.release();
        }
    }
    
    @Override
    public void stopExecution()
    {
        stopFlag.setValue(true);
        if (filter != null) filter.stopFlag.setValue(true);
    }
}
