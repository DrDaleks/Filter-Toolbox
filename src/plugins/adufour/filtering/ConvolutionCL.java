package plugins.adufour.filtering;

import icy.image.IcyBufferedImage;
import icy.sequence.Sequence;
import icy.type.DataType;
import icy.type.collection.array.Array1DUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import plugins.adufour.ezplug.EzException;
import plugins.adufour.vars.lang.VarBoolean;

import com.nativelibs4java.opencl.CLBuildException;
import com.nativelibs4java.opencl.CLContext;
import com.nativelibs4java.opencl.CLEvent;
import com.nativelibs4java.opencl.CLException;
import com.nativelibs4java.opencl.CLFloatBuffer;
import com.nativelibs4java.opencl.CLKernel;
import com.nativelibs4java.opencl.CLMem.MapFlags;
import com.nativelibs4java.opencl.CLMem.Usage;
import com.nativelibs4java.opencl.CLProgram;
import com.nativelibs4java.opencl.CLQueue;

public class ConvolutionCL
{
	public final CLProgram	clProgram;
	public final CLContext	clContext;
	public final CLQueue	clQueue;
	public final CLEvent	clEvent	= null;
	
	public ConvolutionCL(CLContext context, CLProgram program, CLQueue queue)
	{
		clContext = context;
		clQueue = queue;
		clProgram = program;
	}
	
	public void convolve(Sequence input, Sequence kernel, boolean zeroEdge, int nbIter, VarBoolean stopFlag) throws CLException.OutOfHostMemory
	{
		String funcName = zeroEdge ? "convolve2D" : "convolve2D_mirror";
		
		CLKernel clKernel;
		
		try
		{
			clKernel = clProgram.createKernel(funcName);
		}
		catch (CLBuildException e)
		{
			throw new EzException("Unable to load OpenCL function \"" + funcName + "\"", true);
		}
		
		int dataSize = input.getSizeX() * input.getSizeY();
		
		CLEvent event;
		
		float[] data = new float[dataSize];
		
		CLFloatBuffer cl_inBuffer = clContext.createFloatBuffer(Usage.Input, dataSize);
		
		double[] kernelDouble = kernel.getDataXYAsDouble(0, 0, 0);
		
		CLFloatBuffer cl_kBuffer = clContext.createFloatBuffer(Usage.Input, kernelDouble.length);
		FloatBuffer fb_k = cl_kBuffer.map(clQueue, MapFlags.Write);
		for (double d : kernelDouble)
			fb_k.put((float) d);
		fb_k.rewind();
		event = cl_kBuffer.unmap(clQueue, fb_k);
		
		// create a "direct" float buffer
		FloatBuffer outBuffer = ByteBuffer.allocateDirect(dataSize * 4).order(clContext.getByteOrder()).asFloatBuffer();
		// share the reference directly with the GPU (no copy)
		CLFloatBuffer cl_outBuffer = clContext.createFloatBuffer(Usage.Output, outBuffer, false);
		
		// set the kernel arguments in order
		clKernel.setArgs(cl_inBuffer, input.getSizeX(), input.getSizeY(), cl_kBuffer, kernel.getSizeX() >> 1, kernel.getSizeY() >> 1, cl_outBuffer);
		
		FloatBuffer fb;
		
		input.beginUpdate();
		
		DataType type = input.getDataType_();
		
		convolution: for (int t = 0; t < input.getSizeT(); t++)
		{
			for (int z = 0; z < input.getSizeZ(); z++)
			{
				IcyBufferedImage image = input.getImage(t, z);
				
				for (int c = 0; c < input.getSizeC(); c++)
				{
					// convert image to float
					Array1DUtil.arrayToFloatArray(image.getDataXY(c), data, type.isSigned());
					
					for (int i = 0; i < nbIter; i++)
					{
						// map the GPU buffer to local memory
						fb = cl_inBuffer.map(clQueue, MapFlags.Write, event);
						// write the image data to it
						fb.put(data);
						fb.rewind();
						// release the mapping
						event = cl_inBuffer.unmap(clQueue, fb);
						
						// run the GPU code
						event = clKernel.enqueueNDRange(clQueue, new int[] { data.length }, event);
						
						event = cl_outBuffer.read(clQueue, outBuffer, true, event);
						
						// outBuffer shares memory with cl_outBuffer, so values are
						// ready to retrieve
						// NOTE: for some reason this is faster than creating and
						// mapping a GPU buffer
						outBuffer.get(data);
						// rewind the buffer for future iterations
						outBuffer.rewind();
						
						// convert back to image data
						Array1DUtil.floatArrayToSafeArray(data, image.getDataXY(c), type.isSigned());
						
						if (stopFlag.getValue())
							break convolution;
					}
				}
			}
		}
		input.endUpdate();
	}
}
