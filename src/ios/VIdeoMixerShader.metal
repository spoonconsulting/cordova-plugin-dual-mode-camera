#include <metal_stdlib>
using namespace metal;

struct MixerParameters
{
    float2 pipPosition;
    float2 pipSize;
};

constant sampler kBilinearSampler(filter::linear,  coord::pixel, address::clamp_to_edge);

// Compute kernel for picture-in-picture video mixing with circular mask
kernel void reporterMixer(texture2d<half, access::read>        fullScreenInput        [[ texture(0) ]],
                          texture2d<half, access::sample>    pipInput            [[ texture(1) ]],
                          texture2d<half, access::write>    outputTexture        [[ texture(2) ]],
                          const device    MixerParameters&    mixerParameters        [[ buffer(0) ]],
                          uint2 gid [[thread_position_in_grid]])

{
    uint2 pipPosition = uint2(mixerParameters.pipPosition);
    uint2 pipSize = uint2(mixerParameters.pipSize);

    half4 output;

    // Calculate the center and radius of the circular PiP
    float2 pipCenter = float2(pipPosition) + float2(pipSize) * 0.5;
    float radius = min(float(pipSize.x), float(pipSize.y)) * 0.5;
    
    // Distance from current pixel to PiP center
    float2 pixelPos = float2(gid);
    float distanceFromCenter = length(pixelPos - pipCenter);
    
    // Check if pixel is within the circular PiP
    if (distanceFromCenter <= radius)
    {
        // Inside the circle - sample from PiP texture
        // Calculate position relative to PiP bounding box
        float2 relativePos = pixelPos - float2(pipPosition);
        float2 pipSamplingCoord = relativePos * float2(pipInput.get_width(), pipInput.get_height()) / float2(pipSize);
        output = pipInput.sample(kBilinearSampler, pipSamplingCoord + 0.5);
    }
    else
    {
        // Outside the circle - use fullscreen
        output = fullScreenInput.read(gid);
    }

    outputTexture.write(output, gid);
}
