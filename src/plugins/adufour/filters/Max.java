package plugins.adufour.filters;

import icy.math.ArrayMath;

public class Max extends GenericFilterOperation
{
    @Override
    double process(double currentValue, double[] neighborhood)
    {
        return ArrayMath.max(neighborhood);
    }
}
