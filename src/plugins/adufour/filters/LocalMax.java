package plugins.adufour.filters;

import icy.math.ArrayMath;

public class LocalMax extends GenericFilterOperation
{
    @Override
    double process(double currentValue, double[] neighborhood)
    {
        return currentValue >= ArrayMath.max(neighborhood) ? 1.0 : 0.0;
    }
}
