# Vulkan 4-Preview Renderer Rewrite Plan

## Problem
Vulkan FrameGraph assumes 1 scene -> 1 render target per frame. Calling `renderLevel` 4x nested creates 4 FrameGraph executions with conflicting fence states -> `IllegalStateException: Cannot wait on a fence for the current submit`.

## Solution: Single Large Render Target + 4 Quadrant Scissor

Instead of 4 nested `renderLevel` calls:
1. Create **single large RenderTarget** (2x main FB size)
2. Render all 4 views to quadrants of this single target using scissor/viewport
3. Single FrameGraph execution -> no nested fence conflicts
4. Blit 4 quadrants to individual feed textures

## Architecture

```
+-----------------------------------------+
|           Single Large FB (2x2)         |
|  +-----------------+-----------------+   |
|  |  View 0         |  View 1         |   |
|  |  (TL)           |  (TR)           |   |
|  +-----------------+-----------------+   |
|  |  View 2         |  View 3         |   |
|  |  (BL)           |  (BR)           |   |
|  +-----------------+-----------------+   |
+-----------------------------------------+
                |
                v Blit 4 quadrants
+-----------------------------------------+
|  Feed 0  |  Feed 1  |  Feed 2  | Feed 3  |
+-----------------------------------------+
```

## Implementation Steps

### 1. MultiViewRenderer class
- Single large TextureTarget (2x main FB)
- 4 cameras, 4 viewports, 4 scissors
- Single FrameGraph pass with 4 scissor regions

### 2. FrameGraph Integration
- Single `FrameGraph.addPass("pov-multi-view")`
- 4 scissor/viewport changes within single pass
- Each quadrant renders to quadrant of single large target

### 4. Vulkan Compatibility
- Single FrameGraph execution = single fence wait
- No nested FrameGraph calls
- Works on Vulkan and OpenGL

## Key Files to Modify

New:
- `src/main/kotlin/.../pov/MultiViewRenderer.kt`

Modified:
- `PovCapture.kt` - Add `MultiViewRenderer` integration
- `GameRendererMixin.java` - @WrapOperation on renderLevel
- `MinecraftClientMixin.java` - Redirect main render target
- `PovPreviews.kt` - Add "Multi-view" setting

## Next Agent Instructions
1. Start with Phase 1: Create MultiViewRenderer class
2. Test each commit on Vulkan before pushing
3. Commit messages: "feat: multi-view renderer phase 1" etc.
4. Push only to experimental branch
5. Save progress to this file before each commit