// portscan.cpp - TCP connect scanner, tanpa dependensi.
// build: g++ -O2 -std=c++17 portscan.cpp -o portscan

#include <arpa/inet.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>

static bool coba(const std::string& ip, int port, int timeout_ms) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return false;

    sockaddr_in alamat{};
    alamat.sin_family = AF_INET;
    alamat.sin_port = htons(static_cast<uint16_t>(port));
    inet_pton(AF_INET, ip.c_str(), &alamat.sin_addr);

    int flag = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flag | O_NONBLOCK);

    int rc = connect(fd, reinterpret_cast<sockaddr*>(&alamat), sizeof(alamat));
    if (rc < 0 && errno != EINPROGRESS) {
        close(fd);
        return false;
    }

    fd_set set;
    FD_ZERO(&set);
    FD_SET(fd, &set);

    timeval tv{};
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;

    rc = select(fd + 1, nullptr, &set, nullptr, &tv);
    bool terbuka = false;

    if (rc > 0) {
        int err = 0;
        socklen_t len = sizeof(err);
        getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len);
        terbuka = (err == 0);
    }

    close(fd);
    return terbuka;
}

int main(int argc, char** argv) {
    if (argc < 4) {
        std::puts("pemakaian: portscan <ip> <port_awal> <port_akhir> [timeout_ms]");
        return 1;
    }

    std::string ip = argv[1];
    int awal = std::atoi(argv[2]);
    int akhir = std::atoi(argv[3]);
    int timeout_ms = (argc > 4) ? std::atoi(argv[4]) : 400;

    auto mulai = std::chrono::steady_clock::now();
    int jumlah = 0;

    std::printf("[*] target %s port %d-%d\n", ip.c_str(), awal, akhir);

    for (int p = awal; p <= akhir; ++p) {
        if (coba(ip, p, timeout_ms)) {
            std::printf("[+] %d terbuka\n", p);
            ++jumlah;
        }
    }

    auto selesai = std::chrono::steady_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(selesai - mulai).count();
    std::printf("[*] selesai: %d terbuka dalam %lld ms\n", jumlah, static_cast<long long>(ms));

    return jumlah ? 0 : 2;
}
