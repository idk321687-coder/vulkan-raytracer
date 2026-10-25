# Vulkan Compute-Shader Ray Tracer

A real-time GPU path tracer built entirely with Vulkan compute shaders. No rasterization pipeline — every pixel is ray-traced on the GPU.

![screenshot](screenshot.png)

## Features

- **GPU path tracing** via a Vulkan compute shader (4 samples/pixel, 5 bounces)
- **Materials**: metallic reflections, diffuse Lambertian, emissive light sources
- **Checkerboard ground plane** with subtle reflections
- **ACES tone mapping** and gamma correction
- **Animated scene** (bobbing sphere)
- **FPS camera** — WASD + mouse to fly around
- **Window resizing** with automatic swapchain recreation

## Scene

| Sphere | Material |
|--------|----------|
| Large chrome | Perfect mirror |
| Red | Diffuse |
| Blue | Metallic (low roughness) |
| Green | Diffuse |
| Gold | Metallic |
| Purple | Semi-metallic, animated bob |
| Cyan | Near-perfect mirror |
| 2× emissive | Warm + cool area lights |

## Controls

| Key | Action |
|-----|--------|
| **W/A/S/D** | Move forward/left/back/right |
| **Space** | Move up |
| **Shift** | Move down |
| **Mouse** | Look around |
| **ESC** | Toggle mouse capture |
| **Click** | Re-capture mouse |

## Prerequisites

```bash
sudo apt install libglfw3-dev libvulkan-dev glslang-tools cmake build-essential
```

## Build & Run

```bash
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
make -j$(nproc)
./raytracer
```

## How It Works

1. A **compute shader** (`shaders/raytrace.comp`) is dispatched over every pixel
2. Each invocation casts 4 jittered rays through the pixel for anti-aliasing
3. Rays bounce up to 5 times — specular or diffuse depending on material
4. Russian roulette terminates low-energy paths early
5. The result is written to a **storage image**, then blitted to the swapchain for display
6. Push constants pass camera state (position, orientation, time) each frame

## Project Structure

```
├── CMakeLists.txt          # Build system (finds Vulkan, GLFW, compiles shaders)
├── shaders/
│   └── raytrace.comp       # GLSL compute shader — the actual path tracer
├── src/
│   └── main.cpp            # Vulkan setup, window, camera, render loop
└── README.md
```

