package plugins.adufour.filters;

import icy.image.IcyBufferedImage;
import icy.sequence.Sequence;
import icy.system.SystemUtil;
import icy.type.DataType;
import icy.type.collection.array.Array1DUtil;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import plugins.adufour.blocks.lang.Block;
import plugins.adufour.blocks.util.VarList;
import plugins.adufour.ezplug.EzPlug;
import plugins.adufour.ezplug.EzStoppable;
import plugins.adufour.ezplug.EzVarInteger;
import plugins.adufour.ezplug.EzVarPlugin;
import plugins.adufour.ezplug.EzVarSequence;
import plugins.adufour.filtering.ConvolutionException;
import plugins.adufour.filtering.FilterToolbox.Axis;
import plugins.adufour.vars.lang.Var;
import plugins.adufour.vars.lang.VarBoolean;
import plugins.adufour.vars.lang.VarDouble;
import plugins.adufour.vars.util.VarListener;

public class SeparableFilter extends EzPlug implements EzStoppable, Block
{
	EzVarSequence						input		= new EzVarSequence("input");

	EzVarPlugin<GenericFilterOperation>	filterOp	= new EzVarPlugin<GenericFilterOperation>("filter", GenericFilterOperation.class);

	EzVarInteger						radius		= new EzVarInteger("radius", 1, 1, Short.MAX_VALUE, 1);

	VarBoolean							stopFlag	= new VarBoolean("stop", false);

	VarDouble							progress	= new VarDouble("Progression", 0.0);

	@Override
	protected void initialize()
	{
		addEzComponent(input);
		addEzComponent(filterOp);
		addEzComponent(radius);
		setTimeDisplay(true);
	}

	@Override
	protected void execute()
	{
		if (!isHeadLess()) progress.addListener(new VarListener<Double>()
		{
			@Override
			public void valueChanged(Var<Double> source, Double oldValue, Double newValue)
			{
				getUI().setProgressBarValue(newValue);
			}

			@Override
			public void referenceChanged(Var<Double> source, Var<? extends Double> oldReference, Var<? extends Double> newReference)
			{
			}
		});

		Sequence output = filterSquare(stopFlag, input.getValue(true), filterOp.newInstance(), radius.getValue(), radius.getValue(), input.getValue().getSizeZ() == 1 ? 0 : radius.getValue());

		if (!isHeadLess())
		{
			addSequence(output);
			progress.removeListeners();
		}
	}

	/**
	 * Filter the given sequence with the specified non-linear filter on the specified neighborhood.
	 * Note that some operations require double floating-point precision, therefore the input
	 * sequence will be internally converted to double precision. However the result will be
	 * converted back to the same type as the given input sequence <i>with re-scaling</i>.
	 * 
	 * @param stopFlag
	 *            a flag variable that will stop the filtering process if set to true (this flag is
	 *            first set to false when starting this method)
	 * @param sequence
	 *            the sequence to filter (its data will be overwritten)
	 * @param filterType
	 *            the type of filter to apply
	 * @param radius
	 *            the neighborhood radius in each dimension (the actual neighborhood size will be
	 *            <code>1+(2*radius)</code> to ensure it is centered on each pixel). If a single
	 *            value is given, this value is used for all sequence dimensions. If two values are
	 *            given for a 3D sequence, the filter is considered in 2D and applied to each Z
	 *            section independently.
	 */
	public Sequence filterSquare(VarBoolean stopFlag, Sequence sequence, final GenericFilterOperation filter, int... radius)
	{
		Sequence out = new Sequence(sequence.getName() + "_" + filter.getDescriptor().getName());

		stopFlag.setValue(false);
		progress.setValue(0.0);

		if (radius.length == 0) throw new IllegalArgumentException("Provide at least one filter radius");

		final int nThreads = SystemUtil.getAvailableProcessors();

		ExecutorService service = Executors.newFixedThreadPool(nThreads);

		final int width = sequence.getSizeX();
		final int height = sequence.getSizeY();
		final int depth = sequence.getSizeZ();
		final int channels = sequence.getSizeC();
		final DataType type = sequence.getDataType_();
		final boolean signed = sequence.isSignedDataType();

		final double taskIncrement = 1.0 / (height * depth * channels * sequence.getSizeT());

		final int kWidth = radius[0];
		final int kHeight = radius.length == 1 ? kWidth : radius[1];
		final int kDepth = radius.length == 1 ? kWidth : radius.length == 2 ? 1 : radius[2];

		final Object[] in_Z_XY = new Object[depth];

		final Axis[] axes = depth == 1 ? new Axis[] { Axis.X, Axis.Y } : new Axis[] { Axis.X, Axis.Y, Axis.Z };

		// create an array of tasks for multi-thread processing
		// => rationale: one task per image line
		ArrayList<Future<?>> tasks = new ArrayList<Future<?>>(height);

		convolution: for (int t = 0; t < sequence.getSizeT(); t++)
		{
			for (int z = 0; z < depth; z++)
				out.setImage(t, z, new IcyBufferedImage(width, height, channels, type));

			for (int c = 0; c < channels; c++)
			{
				for (int z = 0; z < depth; z++)
					in_Z_XY[z] = sequence.getImage(t, z, c).getDataXY(0);

				for (final Axis axis : axes)
				{
					for (int z = 0; z < depth; z++)
					{
						final int minZinclusive = Math.max(z - kDepth, 0);
						final int maxZexclusive = Math.min(z + kDepth + 1, depth);
						final Object _inXY = in_Z_XY[z];
						final Object _outXY = out.getDataXY(t, z, c);

						// clear the task array
						tasks.clear();

						for (int y = 0; y < height; y++)
						{
							final int line = y;
							final int lineOffset = y * width;

							// submit a new filtering task for the current line
							tasks.add(service.submit(new Runnable()
							{
								@Override
								public void run()
								{
									double[] neighborhood = new double[kWidth * 2 + 1];

									double centerValue = 0.0;

									int inX;
									int inXY, outXY = lineOffset;

									if (axis == Axis.X)
									{
										// process each pixel of the current line
										for (int x = 0; x < width; x++, outXY++)
										{
											int localNeighborHoodSize = 0;
											int minXinclusive = Math.max(x - kWidth, 0);
											int maxXexclusive = Math.min(x + kWidth + 1, width);
											inXY = lineOffset + minXinclusive;

											for (inX = minXinclusive; inX < maxXexclusive; inX++, inXY++, localNeighborHoodSize++)
											{
												double value = Array1DUtil.getValue(_inXY, inXY, type);
												neighborhood[localNeighborHoodSize] = value;
												if (inXY == outXY) centerValue = value;
											}

											// the neighborhood has been browsed and stored.
											// => the filter can be applied here

											Array1DUtil.setValue(_outXY, outXY, type, filter.process(centerValue, neighborhood, localNeighborHoodSize));
										}
									}

									if (line % 3 == 0) progress.setValue(progress.getValue() + taskIncrement * 3);
								}
							}));

							if (stopFlag.getValue()) break;

						} // end for(y)

						try
						{
							for (Future<?> f : tasks)
								f.get();
						}
						catch (InterruptedException e)
						{
							e.printStackTrace();
						}
						catch (ExecutionException e)
						{
							e.printStackTrace();
						}

						if (stopFlag.getValue()) break convolution;
					} // end for(z)
				} // end for(axis)
			} // end for(c)
		} // end for(t)

		service.shutdown();
		return out;
	}

	/**
	 * Low-level 1D convolution method. <br>
	 * Warning: this is a low-level method. No check is performed on the input arguments, and the
	 * method may return successfully though with incorrect results. Make sure your arguments follow
	 * the indicated constraints.
	 * 
	 * @param input
	 *            the input image data buffer, given as a [Z (slice)][XY (1D offset)] double array
	 * @param output
	 *            the output image data buffer, given as a [Z (slice)][XY (1D offset)] double array
	 *            (must point to a different array than the input)
	 * @param width
	 *            the image width
	 * @param height
	 *            the image height
	 * @param kernel
	 *            an odd-length convolution kernel
	 * @param axis
	 *            the axis along which to convolve
	 * @throws ConvolutionException
	 *             if a kernel is too large w.r.t. the image size
	 */
	public void filter1D(double[][] input, double[][] output, int width, int height, int kRadius, GenericFilterOperation kernel, Axis axis) throws ConvolutionException
	{
		try
		{
			int sliceSize = input[0].length;
			
			switch (axis)
			{
				case X:
				{
					for (int z = 0; z < input.length; z++)
					{
						double[] inSlice = input[z];
						double[] outSlice = output[z];
						int xy = 0;
						
						for (int y = 0; y < height; y++)
						{
							int x = 0;
							
							// store the offset of the first and last elements of the line
							// they will be used to compute mirror conditions
							int xStartOffset = xy;
							int xEndOffset = xy + width - 1;
							
							// convolve the west border (mirror condition)
							
							for (; x < kRadius; x++, xy++)
							{
								double value = 0;
								
								for (int kIndex = 0, kOffset = -kRadius; kOffset <= kRadius; kOffset++, kIndex++)
								{
									int inOffset = xy + kOffset;
									if (inOffset < xStartOffset) inOffset = xStartOffset + (xStartOffset - inOffset);
									
									value += inSlice[inOffset] * kernel[kIndex];
								}
								
								outSlice[xy] = value;
							}
							
							// convolve the central area until the east border
							
							int eastBorder = width - kRadius;
							
							for (; x < eastBorder; x++, xy++)
							{
								double value = 0;
								
								for (int kIndex = 0, kOffset = -kRadius; kOffset <= kRadius; kOffset++, kIndex++)
								{
									value += inSlice[xy + kOffset] * kernel[kIndex];
								}
								
								outSlice[xy] = value;
							}
							
							// convolve the east border
							
							for (; x < width; x++, xy++)
							{
								double value = 0;
								
								for (int kIndex = 0, kOffset = -kRadius; kOffset <= kRadius; kOffset++, kIndex++)
								{
									int inOffset = xy + kOffset;
									if (inOffset >= xEndOffset) inOffset = xEndOffset - (inOffset - xEndOffset);
									
									value += inSlice[inOffset] * kernel[kIndex];
								}
								
								outSlice[xy] = value;
							}
						}
					}
				}
				break;
				
				case Y:
				{
					int kRadiusY = kRadius * width;
					
					for (int z = 0; z < input.length; z++)
					{
						double[] in = input[z];
						double[] out = output[z];
						int xy = 0;
						
						int y = 0;
						
						// convolve the north border (mirror condition)
						
						for (; y < kRadius; y++)
						{
							for (int x = 0; x < width; x++, xy++)
							{
								int yStartOffset = x;
								
								double value = 0;
								
								for (int kIndex = 0, kOffset = -kRadiusY; kOffset <= kRadiusY; kOffset += width, kIndex++)
								{
									int inOffset = xy + kOffset;
									if (inOffset < 0) inOffset = yStartOffset - inOffset;
									
									value += in[inOffset] * kernel[kIndex];
								}
								
								out[xy] = value;
							}
						}
						
						// convolve the central area until the south border
						
						int southBorder = height - kRadius;
						
						for (; y < southBorder; y++)
						{
							for (int x = 0; x < width; x++, xy++)
							{
								double value = 0;
								
								for (int kIndex = 0, kOffset = -kRadiusY; kOffset <= kRadiusY; kOffset += width, kIndex++)
								{
									value += in[xy + kOffset] * kernel[kIndex];
								}
								
								out[xy] = value;
							}
						}
						
						// convolve the south border
						
						for (; y < height; y++)
						{
							for (int x = 0; x < width; x++, xy++)
							{
								int yEndOffset = sliceSize - width + x;
								
								double value = 0;
								
								for (int kIndex = 0, kOffset = -kRadiusY; kOffset <= kRadiusY; kOffset += width, kIndex++)
								{
									int inOffset = xy + kOffset;
									if (inOffset >= sliceSize) inOffset = yEndOffset - (inOffset - yEndOffset);
									
									value += in[inOffset] * kernel[kIndex];
								}
								
								out[xy] = value;
							}
						}
					}
				}
				break;
				
				case Z:
				{
					int z = 0;
					for (; z < kRadius; z++)
					{
						double[] out = output[z];
						
						int xy = 0;
						
						for (int y = 0; y < height; y++)
						{
							for (int x = 0; x < width; x++, xy++)
							{
								double value = 0;
								
								for (int kIndex = 0, kOffset = -kRadius; kOffset <= kRadius; kOffset++, kIndex++)
								{
									int inSlice = z + kOffset;
									if (inSlice < 0) inSlice = -inSlice;
									
									value += input[inSlice][xy] * kernel[kIndex];
								}
								
								out[xy] = value;
							}
						}
					}
					
					int bottomBorder = input.length - kRadius;
					
					for (; z < bottomBorder; z++)
					{
						double[] out = output[z];
						
						int xy = 0;
						
						for (int y = 0; y < height; y++)
						{
							for (int x = 0; x < width; x++, xy++)
							{
								double value = 0;
								
								for (int kIndex = 0, kOffset = -kRadius; kOffset <= kRadius; kOffset++, kIndex++)
								{
									value += input[z + kOffset][xy] * kernel[kIndex];
								}
								
								out[xy] = value;
							}
						}
					}
					
					int zEndOffset = input.length - 1;
					
					for (; z < input.length; z++)
					{
						double[] out = output[z];
						
						int xy = 0;
						
						for (int y = 0; y < height; y++)
						{
							for (int x = 0; x < width; x++, xy++)
							{
								double value = 0;
								
								for (int kIndex = 0, kOffset = -kRadius; kOffset <= kRadius; kOffset++, kIndex++)
								{
									int inSlice = z + kOffset;
									if (inSlice >= input.length) inSlice = zEndOffset - (inSlice - zEndOffset);
									
									value += input[inSlice][xy] * kernel[kIndex];
								}
								
								out[xy] = value;
							}
						}
					}
				}
				break;
			}
		}
		catch (ArrayIndexOutOfBoundsException e)
		{
			throw new ConvolutionException("Filter size is too large along " + axis.name(), e);
		}
	}

	
	@Override
	public void clean()
	{
		// TODO Auto-generated method stub

	}

	@Override
	public void declareInput(VarList inputMap)
	{
		// TODO Auto-generated method stub

	}

	@Override
	public void declareOutput(VarList outputMap)
	{
		// TODO Auto-generated method stub

	}

	@Override
	public void stopExecution()
	{
		stopFlag.setValue(true);
	}

}
