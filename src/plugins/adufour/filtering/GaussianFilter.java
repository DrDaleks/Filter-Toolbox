package plugins.adufour.filtering;

import icy.plugin.abstract_.Plugin;
import icy.plugin.interface_.PluginBundled;
import icy.sequence.Sequence;
import plugins.adufour.blocks.lang.Block;
import plugins.adufour.blocks.util.VarList;
import plugins.adufour.vars.gui.model.DoubleRangeModel;
import plugins.adufour.vars.gui.model.RangeModel;
import plugins.adufour.vars.lang.VarDouble;
import plugins.adufour.vars.lang.VarSequence;
import plugins.adufour.vars.util.VarException;

/**
 * Block version of the filter tool box for the specific case of Gaussian filtering
 * 
 * @author Alexandre Dufour
 * 
 */
public class GaussianFilter extends Plugin implements Block, PluginBundled
{
    static int  id     = 0;
    
    VarSequence input  = new VarSequence("input", null);
    
    VarDouble   gX     = new VarDouble("sigma (x)", 0.0);
    VarDouble   gY     = new VarDouble("sigma (y)", 0.0);
    VarDouble   gZ     = new VarDouble("sigma (z)", 0.0);
    
    VarSequence output = new VarSequence("output", null);
    
    @Override
    public void run()
    {
        double[] gaussianX = Kernels1D.CUSTOM_GAUSSIAN.createGaussianKernel1D(gX.getValue(true)).getData();
        double[] gaussianY = Kernels1D.CUSTOM_GAUSSIAN.createGaussianKernel1D(gY.getValue(true)).getData();
        double[] gaussianZ = Kernels1D.CUSTOM_GAUSSIAN.createGaussianKernel1D(gZ.getValue(true)).getData();
        
        Sequence filtered = input.getValue(true).getCopy();
        filtered.setName(input.getValue().getName() + "_filtered");
        
        try
        {
            Convolution1D.convolve(filtered, gaussianX == null ? null : gaussianX, gaussianY == null ? null : gaussianY, gaussianZ == null ? null : filtered.getSizeZ() > 1 ? gaussianZ : null);
        }
        catch (Exception e)
        {
            throw new VarException("GaussianFilter: " + e.getMessage());
        }
        
        output.setValue(filtered);
    }
    
    @Override
    public void declareInput(VarList inputMap)
    {
        RangeModel<Double> constraint = new DoubleRangeModel(0.0, 0.0, 1000.0, 0.1);
        gX.setDefaultEditorModel(constraint);
        gY.setDefaultEditorModel(constraint);
        gZ.setDefaultEditorModel(constraint);
        inputMap.add("input", input);
        inputMap.add("filterX", gX);
        inputMap.add("filterY", gY);
        inputMap.add("filterZ", gZ);
    }
    
    @Override
    public void declareOutput(VarList outputMap)
    {
        outputMap.add("output", output);
    }
    
    @Override
    public String getMainPluginClassName()
    {
        return FilterToolbox.class.getCanonicalName();
    }
    
}
