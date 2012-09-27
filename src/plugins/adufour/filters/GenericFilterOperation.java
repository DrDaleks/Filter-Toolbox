package plugins.adufour.filters;

import icy.plugin.abstract_.Plugin;

public abstract class GenericFilterOperation extends Plugin
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
     * @return
     */
    abstract double process(double currentValue, double[] neighborhood);
}
