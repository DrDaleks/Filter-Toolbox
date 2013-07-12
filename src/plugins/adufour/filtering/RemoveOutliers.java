package plugins.adufour.filtering;

public class RemoveOutliers extends SelectionFilter
{
    
    @Override
    double process(double currentValue, double[] neighborhood, int neighborHoodSize)
    {
        double mean = 0, stdev2 = 0;
        
        // mean
        for (int i = 0; i < neighborHoodSize; i++)
            mean += neighborhood[i];
        mean /= neighborHoodSize;
        
        // standard deviation
        for (int i = 0; i < neighborHoodSize; i++)
            stdev2 += (neighborhood[i] - mean) * (neighborhood[i] - mean);
        stdev2 = Math.sqrt(stdev2 / neighborHoodSize);
        
        // threshold: 2 standard deviations by the mean
        stdev2 += stdev2;
        
        if (currentValue > mean + stdev2 || currentValue < mean - stdev2)
        {
            // return the mean of the data without the outlier
            mean = 0.0;
            for (int i = 0; i < neighborHoodSize; i++)
                if (neighborhood[i] != currentValue) mean += neighborhood[i];
            return mean / (neighborHoodSize - 1);
        }
        
        return currentValue;
    }
    
}
