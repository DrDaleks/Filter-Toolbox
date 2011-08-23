__kernel void convolve2D(__global float* input,		// the input image as a 1D linear array
                         int inputWidth,			// the image width
                         int inputHeight,			// the image height
                         __global float* k,			// the kernel as a 1D linear array
                         int kWidth,				// the rounded kernel half-width
                         int kHeight,               // the rounded kernel half-height
                         __global float* output)	// the output image as a 1D linear array
{
	int pixel = get_global_id(0);
	
	int inX, inY, inXY = 0, kXY = 0;
	float iSum = 0.f;
	const int x = pixel % inputWidth;
	const int y = pixel / inputWidth;
	
	for (int kY = -kHeight; kY <= kHeight; kY++) {
		inY = y + kY;
		if (inY < 0 || inY >= inputHeight) continue; // zero boundary condition
		inXY = inY * inputWidth;
		for (int kX = -kWidth; kX <= kWidth; kX++, kXY++) {
			inX = x + kX;
			if (inX < 0 || inX >= inputWidth) continue; // zero boundary condition
			iSum += input[inXY + inX] * k[kXY];
		}
	}
	output[pixel] = iSum;
}

__kernel void convolve2D_mirror(__global float* input, 	// the input image as a 1D linear array
                    	        int inputWidth,			// the image width
                    	        int inputHeight,		// the image height
                   		        __global float* k,		// the kernel as a 1D linear array
                   		        int kWidth,				// the rounded kernel half-width
                   		        int kHeight,            // the rounded kernel half-height
                   		        __global float* output)	// the output image as a 1D linear array
{
	int pixel = get_global_id(0);
	
	int inX, inY, inXY = 0, kXY = 0;
	float iSum = 0.f;
	const int x = pixel % inputWidth;
	const int y = pixel / inputWidth;
	
	for (int kY = -kHeight; kY <= kHeight; kY++) {
		inY = y + kY;
		// mirror boundary condition
		if (inY < 0) {
			inY = -inY + 1;
		} else if (inY >= inputHeight) {
			inY = (inputHeight << 1) - inY - 1;
		}
		inXY = inY * inputWidth;
		// sweep through the kernel along X
		for (int kX = -kWidth; kX <= kWidth; kX++, kXY++) {
			inX = x + kX;
			// mirror boundary condition
			if (inX < 0) {
				inX = -inX + 1;
			} else if (inX >= inputWidth) {
				inX = (inputWidth << 1) - inX - 1;
			}
			iSum += input[inXY + inX] * k[kXY];
		}
	}
	output[pixel] = iSum;
}
