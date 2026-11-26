/* A normal leaf library in a small DT_NEEDED chain. */
static int initialized;
__attribute__((constructor)) static void initialize(void) { initialized = 17; }
int fixture_dependency_leaf(void) { return initialized; }
