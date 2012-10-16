package plugins.adufour.filtering;

import icy.plugin.abstract_.Plugin;
import icy.system.SystemUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import plugins.adufour.vars.lang.VarBoolean;
import plugins.adufour.vars.lang.VarDouble;

public abstract class Filter extends Plugin
{
    public final ExecutorService service = Executors.newFixedThreadPool(SystemUtil.getAvailableProcessors());
    
    public final VarBoolean stopFlag = new VarBoolean("stop", false);
    
    public final VarDouble  progress = new VarDouble("progression", 0.0);
}
