/* Native Finder entry point. Opening a .command with Terminal requires no
 * AppleScript automation permission. Agent execution stays in the terminal. */
#include <mach-o/dyld.h>
#include <limits.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

int main(int argc, char **argv) {
    char executable[PATH_MAX], resolved[PATH_MAX], command[PATH_MAX];
    uint32_t size = sizeof(executable);
    if (_NSGetExecutablePath(executable, &size) || !realpath(executable, resolved)) {
        perror("Cannot locate application");
        return 1;
    }
    char *separator = strrchr(resolved, '/');
    if (!separator) return 1;
    *separator = '\0';
    if (snprintf(command, sizeof(command), "%s/../Resources/Start.command", resolved)
            >= (int)sizeof(command) || access(command, R_OK)) {
        perror("Cannot locate terminal launcher");
        return 1;
    }
    if (argc == 2 && strcmp(argv[1], "--check") == 0) {
        puts(command);
        return 0;
    }
    char *arguments[] = {"/usr/bin/open", "-a", "Terminal", command, NULL};
    pid_t child;
    int result = posix_spawn(&child, arguments[0], NULL, NULL, arguments, environ);
    if (result) { fprintf(stderr, "Cannot open Terminal: %d\n", result); return 1; }
    int status;
    if (waitpid(child, &status, 0) < 0) { perror("waitpid"); return 1; }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 1;
}
