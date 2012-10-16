package plugins.adufour.filtering;

public class Variance extends SelectionFilter
{
    @Override
    double process(double currentValue, double[] neighborhood, int neighborHoodSize)
    {
        double mean = 0, var = 0;
        
        for (int i = 0; i < neighborHoodSize; i++)
            mean += neighborhood[i];
        mean /= neighborHoodSize;
        
        for (int i = 0; i < neighborHoodSize; i++)
            var += (neighborhood[i] - mean) * (neighborhood[i] - mean);
        
        return var / neighborHoodSize;
    }
}
