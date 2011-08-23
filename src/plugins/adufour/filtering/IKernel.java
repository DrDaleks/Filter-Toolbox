package plugins.adufour.filtering;

import icy.sequence.Sequence;

public interface IKernel
{
	/**
	 * Creates a sequence with the internal data of this kernel
	 * @return
	 */
	Sequence toSequence();
	
	/**
	 * Returns a reference to the internal data of this kernel
	 * @return
	 */
	double[] getData();
}
