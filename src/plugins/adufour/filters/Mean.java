package plugins.adufour.filters;

import icy.math.ArrayMath;

public class Mean extends GenericFilterOperation
{
    @Override
    double process(double currentValue, double[] neighborhood)
    {
        return ArrayMath.mean(neighborhood);
    }
}
