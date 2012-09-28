package plugins.adufour.filters;

public class Mean extends GenericFilterOperation
{
    @Override
    double process(double currentValue, double[] neighborhood, int neighborhoodSize)
    {
        double sum = neighborhood[0];
        for (int i = 1; i < neighborhoodSize; i++)
            sum += neighborhood[i];
        return sum / neighborhoodSize;
    }
}
