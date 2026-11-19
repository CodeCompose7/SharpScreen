// AOA (Android Open Accessory) transport experiment for issue #3.
//
// Switches an Android device into accessory mode without ADB and measures the
// accessory's bulk endpoints against the debug app's AoaSpikeActivity.
//
//   clang -O2 -o aoa_spike scripts/aoa_spike.c \
//     -I/opt/homebrew/include/libusb-1.0 -L/opt/homebrew/lib -lusb-1.0
//   ./aoa_spike [MB]
//
// Needs libusb (brew install libusb). Throwaway measurement tool; the real
// transport would use IOUSBHost so the app ships no extra library.

#include <libusb.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define GOOGLE_VID 0x18D1
#define AOA_PID 0x2D00
#define AOA_ADB_PID 0x2D01

static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static int is_accessory(const struct libusb_device_descriptor *d) {
    return d->idVendor == GOOGLE_VID && (d->idProduct == AOA_PID || d->idProduct == AOA_ADB_PID);
}

static libusb_device_handle *open_accessory(libusb_context *ctx) {
    libusb_device **list;
    ssize_t n = libusb_get_device_list(ctx, &list);
    libusb_device_handle *h = NULL;
    for (ssize_t i = 0; i < n && !h; i++) {
        struct libusb_device_descriptor d;
        if (libusb_get_device_descriptor(list[i], &d) == 0 && is_accessory(&d)) {
            int r = libusb_open(list[i], &h);
            if (r) { fprintf(stderr, "open accessory: %s\n", libusb_error_name(r)); h = NULL; }
            else printf("accessory %04x:%04x, speed %d (1=low 2=full 3=high/USB2 4=super/USB3 5=super+)\n",
                        d.idVendor, d.idProduct, libusb_get_device_speed(list[i]));
        }
    }
    libusb_free_device_list(list, 1);
    return h;
}

// Send the AOA handshake to the first Android-looking device (Samsung here).
static int switch_to_accessory(libusb_context *ctx) {
    libusb_device **list;
    ssize_t n = libusb_get_device_list(ctx, &list);
    int done = 0;
    for (ssize_t i = 0; i < n && !done; i++) {
        struct libusb_device_descriptor d;
        if (libusb_get_device_descriptor(list[i], &d) || d.idVendor != 0x04E8) continue;
        libusb_device_handle *h;
        int r = libusb_open(list[i], &h);
        if (r) { fprintf(stderr, "open %04x:%04x: %s\n", d.idVendor, d.idProduct, libusb_error_name(r)); continue; }
        uint8_t ver[2] = {0};
        r = libusb_control_transfer(h, 0xC0, 51, 0, 0, ver, 2, 1000);
        printf("device %04x:%04x, AOA protocol: %s (%d)\n", d.idVendor, d.idProduct,
               r == 2 ? "yes" : libusb_error_name(r), ver[0] | ver[1] << 8);
        if (r == 2 && (ver[0] | ver[1] << 8) >= 1) {
            const char *s[] = {"SharpScreen", "SharpScreen AOA Spike", "SharpScreen transport test",
                               "1.0", "https://github.com/CodeCompose7/SharpScreen", "spike-0001"};
            for (int k = 0; k < 6; k++)
                libusb_control_transfer(h, 0x40, 52, 0, k, (unsigned char *)s[k], strlen(s[k]) + 1, 1000);
            r = libusb_control_transfer(h, 0x40, 53, 0, 0, NULL, 0, 1000);
            printf("ACCESSORY_START: %s\n", r == 0 ? "ok" : libusb_error_name(r));
            done = 1;
        }
        libusb_close(h);
    }
    libusb_free_device_list(list, 1);
    return done;
}

static unsigned char ep_in, ep_out;

static int find_endpoints(libusb_device_handle *h) {
    struct libusb_config_descriptor *c;
    if (libusb_get_active_config_descriptor(libusb_get_device(h), &c)) return 0;
    const struct libusb_interface_descriptor *alt = &c->interface[0].altsetting[0];
    for (int e = 0; e < alt->bNumEndpoints; e++) {
        const struct libusb_endpoint_descriptor *ep = &alt->endpoint[e];
        if ((ep->bmAttributes & 3) != LIBUSB_TRANSFER_TYPE_BULK) continue;
        if (ep->bEndpointAddress & 0x80) ep_in = ep->bEndpointAddress; else ep_out = ep->bEndpointAddress;
        printf("endpoint 0x%02x, max packet %d\n", ep->bEndpointAddress, ep->wMaxPacketSize);
    }
    libusb_free_config_descriptor(c);
    return ep_in && ep_out;
}

static int xfer(libusb_device_handle *h, unsigned char ep, void *buf, int len) {
    int done = 0, got;
    while (done < len) {
        int r = libusb_bulk_transfer(h, ep, (unsigned char *)buf + done, len - done, &got, 10000);
        if (r) { fprintf(stderr, "bulk 0x%02x: %s after %d/%d\n", ep, libusb_error_name(r), done, len); return -1; }
        done += got;
    }
    return 0;
}

static int command(libusb_device_handle *h, uint32_t cmd, uint64_t a1, uint32_t a2) {
    unsigned char b[20];
    memcpy(b, "AOAS", 4);
    memcpy(b + 4, &cmd, 4);
    memcpy(b + 8, &a1, 8);
    memcpy(b + 16, &a2, 4);
    return xfer(h, ep_out, b, 20);
}

static int cmp_double(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : x > y;
}

static void echo_test(libusb_device_handle *h, int count, int size) {
    unsigned char *out = malloc(size), *in = malloc(size);
    double *rtt = malloc(sizeof(double) * count);
    for (int i = 0; i < size; i++) out[i] = (unsigned char)(i * 7);
    if (command(h, 3, count, size)) return;
    for (int i = 0; i < count; i++) {
        double t = now_ms();
        if (xfer(h, ep_out, out, size) || xfer(h, ep_in, in, size)) return;
        rtt[i] = now_ms() - t;
        if (memcmp(in, out, size)) { fprintf(stderr, "echo mismatch\n"); return; }
    }
    qsort(rtt, count, sizeof(double), cmp_double);
    printf("ECHO %6d B x %d: rtt p50 %.3f ms, p90 %.3f ms, p99 %.3f ms, max %.3f ms\n", size, count,
           rtt[count / 2], rtt[count * 9 / 10], rtt[count * 99 / 100], rtt[count - 1]);
    free(out); free(in); free(rtt);
}

int main(int argc, char **argv) {
    int mb = argc > 1 ? atoi(argv[1]) : 128;
    libusb_context *ctx;
    libusb_init(&ctx);

    libusb_device_handle *h = open_accessory(ctx);
    if (!h) {
        if (!switch_to_accessory(ctx)) { fprintf(stderr, "no AOA-capable device\n"); return 1; }
        for (int i = 0; i < 50 && !h; i++) { usleep(100000); h = open_accessory(ctx); }
        if (!h) { fprintf(stderr, "accessory did not appear\n"); return 1; }
    }
    if (!find_endpoints(h)) { fprintf(stderr, "no bulk endpoints\n"); return 1; }
    int r = libusb_claim_interface(h, 0);
    if (r) { fprintf(stderr, "claim: %s\n", libusb_error_name(r)); return 1; }
    printf("Open the accessory prompt on the tablet if it asks; waiting 1 s...\n");
    sleep(1);

    // Mac -> tablet (the video direction).
    size_t total = (size_t)mb << 20, chunk = 1 << 20;
    unsigned char *buf = malloc(chunk);
    memset(buf, 0x5A, chunk);
    if (command(h, 1, total, 0)) return 1;
    double t = now_ms();
    for (size_t s = 0; s < total; s += chunk) if (xfer(h, ep_out, buf, chunk)) return 1;
    int64_t dev_ns;
    if (xfer(h, ep_in, &dev_ns, 8)) return 1;
    double ms = now_ms() - t;
    printf("Mac -> tablet: %d MB in %.1f ms = %.1f MB/s (tablet clock %.1f ms)\n", mb, ms, total / (ms / 1e3) / 1e6, dev_ns / 1e6);

    // Tablet -> Mac.
    if (command(h, 2, total, 0)) return 1;
    t = now_ms();
    for (size_t s = 0; s < total; s += chunk) if (xfer(h, ep_in, buf, chunk)) return 1;
    ms = now_ms() - t;
    printf("tablet -> Mac: %d MB in %.1f ms = %.1f MB/s\n", mb, ms, total / (ms / 1e3) / 1e6);

    // Round trips: touch-sized, a typical P-frame, a large I-frame.
    echo_test(h, 2000, 100);
    echo_test(h, 500, 30000);
    echo_test(h, 100, 300000);

    libusb_release_interface(h, 0);
    libusb_close(h);
    libusb_exit(ctx);
    return 0;
}
