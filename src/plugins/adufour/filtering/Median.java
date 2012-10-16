package plugins.adufour.filtering;

public class Median extends SelectionFilter
{
    /**
     * (routine ported from 'Numerical Recipes in C 2nd ed.')<br>
     * Computes the k-th smallest value in the input array and rearranges the array such that the
     * wanted value is located at data[k-1], Lower values are stored in arbitrary order in data[0 ..
     * k-2] Higher values will be stored in arbitrary order in data[k .. end]
     * 
     * @return the k-th smallest value in the array
     */
    @Override
    double process(double currentValue, double[] data, int length)
    {
        int k = length >> 1;
        
        int i, j, mid, l = 1;
        double a, temp;
        
        while (true)
        {
            if (length <= l + 1)
            {
                if (length == l + 1 && data[length - 1] < data[l - 1])
                {
                    temp = data[l - 1];
                    data[l - 1] = data[length - 1];
                    data[length - 1] = temp;
                }
                return data[k - 1];
            }
            
            mid = (l + length) >> 1;
            temp = data[mid - 1];
            data[mid - 1] = data[l];
            data[l] = temp;
            
            if (data[l] > data[length - 1])
            {
                temp = data[l + 1 - 1];
                data[l] = data[length - 1];
                data[length - 1] = temp;
            }
            
            if (data[l - 1] > data[length - 1])
            {
                temp = data[l - 1];
                data[l - 1] = data[length - 1];
                data[length - 1] = temp;
            }
            
            if (data[l] > data[l - 1])
            {
                temp = data[l];
                data[l] = data[l - 1];
                data[l - 1] = temp;
            }
            
            i = l + 1;
            j = length;
            a = data[l - 1];
            
            while (true)
            {
                do
                    i++;
                while (data[i - 1] < a);
                do
                    j--;
                while (data[j - 1] > a);
                if (j < i) break;
                
                temp = data[i - 1];
                data[i - 1] = data[j - 1];
                data[j - 1] = temp;
            }
            
            data[l - 1] = data[j - 1];
            data[j - 1] = a;
            
            if (j >= k) length = j - 1;
            if (j <= k) l = i;
        }
    }
}
