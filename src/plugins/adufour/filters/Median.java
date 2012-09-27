package plugins.adufour.filters;

import icy.math.ArrayMath;

public class Median extends GenericFilterOperation
{
    @Override
    double process(double currentValue, double[] neighborhood)
    {
        return ArrayMath.median(neighborhood, false);
    }
}
