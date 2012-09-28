package plugins.adufour.filters;

import java.util.Arrays;

public class Median extends GenericFilterOperation
{
    @Override
    double process(double currentValue, double[] neighborhood, int neighborhoodSize)
    {
        Arrays.sort(neighborhood, 0, neighborhoodSize);
        return neighborhood[neighborhoodSize / 2 + 1];
    }
}
