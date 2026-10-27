#include <stdio.h>

int main(void) {
    printf("Hello from {{label}} in C\n");
    for (int i = 1; i <= 3; i++) {
        printf("%d squared is %d\n", i, i * i);
    }
    return 0;
}
