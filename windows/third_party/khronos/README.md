# Khronos headers

The OpenGL ES and EGL headers the Windows host compiles its GL bindings and
`host/gl/gl_stubs.inc` against. MIT licensed (see the notice at the top of each
file), copied unchanged from:

- `GLES/`, `GLES2/`, `GLES3/`: KhronosGroup/OpenGL-Registry at
  6af574a14089ccfee87efe230ebcdd8742859813 (`api/`)
- `EGL/`, `KHR/`: KhronosGroup/EGL-Registry at
  ddda26d7b06eb3f3b8c6a9f3b418030dcc83b91b (`api/`)

After updating them, run `windows/tools/gen_gl_stubs.py`.
