package plugins.adufour.filtering;

import icy.sequence.Sequence;
import icy.type.DataType;
import icy.type.collection.array.Array1DUtil;
import plugins.adufour.vars.lang.VarBoolean;

public class Convolution
{
	/**
	 * Convolve the input sequence with the given kernel with the specified edge condition.
	 * 
	 * @param input
	 *            the input sequence
	 * @param kernel
	 *            the convolution kernel (1D, 2D or 3D), assumed to be already normalized. If the
	 *            kernel has more than one channel or time point, only the first c,t are used for
	 *            convolution
	 * @param zeroEdges
	 *            true if data outside the sequence should be treated as zero, or false for
	 *            mirroring condition
	 * @param nbIter
	 *            the number of filter iterations
	 */
	public static void convolve(Sequence input, Sequence kernel, boolean zeroEdges, int nbIter, VarBoolean stopFlag)
	{
		DataType type = input.getDataType_();

		final int kWidth = kernel.getSizeX() >> 1;
		final int kHeight = kernel.getSizeY() >> 1;
		final int kDepth = kernel.getSizeZ() >> 1;

		// temporary buffers
		double[][] _inZXY = new double[input.getSizeZ()][input.getSizeX() * input.getSizeY()];
		double[] _outXY = new double[input.getSizeX() * input.getSizeY()];
		double[][] _kernel = kernel.getDataXYZAsDouble(0, 0);

		convolution: for (int t = 0; t < input.getSizeT(); t++)
		{
			for (int c = 0; c < input.getSizeC(); c++)
			{
				// retrieve the input data in double format for convolution

				for (int z = 0; z < _inZXY.length; z++)
				{
					Array1DUtil.arrayToDoubleArray(input.getDataXY(t, z, c), _inZXY[z], type.isSigned());
				}

				for (int i = 0; i < nbIter; i++)
				{
					for (int z = 0; z < _inZXY.length; z++)
					{
						int kX, inX, kY, inY, kZ, inZ;
						int kXY, inXY, outXY = 0;

						for (int y = 0; y < input.getSizeY(); y++)
						{
							for (int x = 0; x < input.getSizeX(); x++, outXY++)
							{
								// core convolution code

								double conv = 0;

								// sweep through the kernel along Z
								for (kZ = -kDepth; kZ <= kDepth; kZ++)
								{
									inZ = z + kZ;

									// mirror boundary condition
									if (inZ < 0)
									{
										if (zeroEdges) continue;

										inZ = -inZ + 1;
									}
									else if (inZ >= input.getSizeZ())
									{
										if (zeroEdges) continue;

										inZ = (input.getSizeZ() * 2) - inZ - 1;
									}

									kXY = 0;

									// sweep through the kernel along Y
									for (kY = -kHeight; kY <= kHeight; kY++)
									{
										inY = y + kY;

										// mirror boundary condition
										if (inY < 0)
										{
											if (zeroEdges) continue;

											inY = -inY + 1;
										}
										else if (inY >= input.getSizeY())
										{
											if (zeroEdges) continue;

											inY = (input.getSizeY() * 2) - inY - 1;
										}

										// this is the line offset
										inXY = inY * input.getSizeX();

										// sweep through the kernel along X
										for (kX = -kWidth; kX <= kWidth; kX++, kXY++)
										{
											inX = x + kX;

											// mirror boundary condition
											if (inX < 0)
											{
												if (zeroEdges) continue;

												inX = -inX + 1;
											}
											else if (inX >= input.getSizeX())
											{
												if (zeroEdges) continue;

												inX = (input.getSizeX() * 2) - inX - 1;
											}

											// Enough of this crap ! convolve god damn it !!

											conv += _inZXY[inZ][inXY + inX] * _kernel[kZ][kXY];
										}
									}
								}
								// store the result in the temporary buffer
								_outXY[outXY] = conv;
							}
						}

						// ArrayMath.rescale(_outXY, input.getComponentMinValue(c),
						// input.getComponentMaxValue(c), true);
						Array1DUtil.doubleArrayToSafeArray(_outXY, input.getDataXY(t, z, c), type.isSigned());
					} // end for(z)
					if (stopFlag.getValue()) break convolution;
				} // end for(i)
			} // end for(c)
		} // end for(t)
	}
}
