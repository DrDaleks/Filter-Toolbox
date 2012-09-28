package plugins.adufour.filters;

public class LocalMax extends GenericFilterOperation
{
    @Override
    double process(double currentValue, double[] neighborhood, int neighborhoodSize)
    {
        double defaultValue = 0.0;
        
        for (int i = 0; i < neighborhoodSize; i++)
        {
            double d = neighborhood[i];
            if (d > currentValue) return 0.0;
            if (defaultValue == 0.0 && d < currentValue) defaultValue = 1.0;
        }
        
        return defaultValue;
    }
}
