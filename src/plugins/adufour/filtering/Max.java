package plugins.adufour.filtering;

public class Max extends SelectionFilter
{
    @Override
    double process(double currentValue, double[] neighborhood, int neighborhoodSize)
    {
        double max = neighborhood[0];
        for (int i = 1; i < neighborhoodSize; i++)
        {
            double d = neighborhood[i];
            if (d > max) max = d;
        }
        return max;
    }
}
