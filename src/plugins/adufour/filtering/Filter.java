package plugins.adufour.filtering;

import icy.plugin.abstract_.Plugin;
import icy.system.SystemUtil;
import icy.system.thread.Processor;
import plugins.adufour.vars.lang.VarBoolean;
import plugins.adufour.vars.lang.VarDouble;

public abstract class Filter extends Plugin
{
    public final Processor service;
    
    public Filter()
    {
        service = new Processor(SystemUtil.getAvailableProcessors() * 2);
        service.setDefaultThreadName(getDescriptor().getName());
    }
    
    public final VarBoolean stopFlag = new VarBoolean("stop", false);
    
    public final VarDouble  progress = new VarDouble("progression", 0.0);
}
