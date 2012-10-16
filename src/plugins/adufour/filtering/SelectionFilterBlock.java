package plugins.adufour.filtering;

import icy.plugin.abstract_.Plugin;
import plugins.adufour.blocks.lang.Block;
import plugins.adufour.blocks.util.VarList;
import plugins.adufour.ezplug.EzVarInteger;
import plugins.adufour.vars.lang.VarPlugin;
import plugins.adufour.vars.lang.VarSequence;

public class SelectionFilterBlock extends Plugin implements Block
{
    VarPlugin<SelectionFilter> filter  = new VarPlugin<SelectionFilter>("filter", SelectionFilter.class);
    
    VarSequence                input   = new VarSequence("input sequence", null);
    
    EzVarInteger               radiusX = new EzVarInteger("filter radius (X)", 1, 0, Short.MAX_VALUE, 1);
    
    EzVarInteger               radiusY = new EzVarInteger("filter radius (Y)", 1, 0, Short.MAX_VALUE, 1);
    
    EzVarInteger               radiusZ = new EzVarInteger("filter radius (Z)", 1, 0, Short.MAX_VALUE, 1);
    
    VarSequence                output  = new VarSequence("filtered sequence", null);
    
    @Override
    public void run()
    {
        SelectionFilter selectionFilter;
        try
        {
            selectionFilter = (SelectionFilter) filter.getValue(true).getPluginClass().newInstance();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Unable to create the " + filter.getValueAsString() + " filter");
        }
        
        output.setValue(selectionFilter.filterSquare(input.getValue(true), radiusX.getValue(), radiusY.getValue(), radiusZ.getValue()));
    }
    
    @Override
    public void declareInput(VarList inputMap)
    {
        inputMap.add(input);
        inputMap.add(filter);
        inputMap.add(radiusX.getVariable());
        inputMap.add(radiusY.getVariable());
        inputMap.add(radiusZ.getVariable());
    }
    
    @Override
    public void declareOutput(VarList outputMap)
    {
        outputMap.add(output);
    }
    
}
