/*
    Infrared front end for melonDS Android.
    GPL-3.0-or-later, see PwCommon.h.
*/

#include "PokewalkerIR.h"

#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <thread>

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "VirtualPokewalker.h"

namespace pokewalker::ir
{

namespace
{

// Silence on the link that marks the end of a packet (same value the real
// cartridge timing uses).
constexpr int kPacketGapUs = 3500;

class TcpLink
{
public:
    ~TcpLink() { Stop(); }

    bool Start(int port, std::string* error)
    {
        Stop();
        listenFd = socket(AF_INET, SOCK_STREAM, 0);
        if (listenFd < 0) { if (error) *error = std::strerror(errno); return false; }
        int one = 1;
        setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        sockaddr_in addr {};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_ANY);
        addr.sin_port = htons((uint16_t)port);
        if (bind(listenFd, (sockaddr*)&addr, sizeof(addr)) != 0 || listen(listenFd, 1) != 0)
        {
            if (error) *error = std::string("cannot listen on port ") + std::to_string(port) + ": " + std::strerror(errno);
            close(listenFd);
            listenFd = -1;
            return false;
        }
        fcntl(listenFd, F_SETFL, fcntl(listenFd, F_GETFL) | O_NONBLOCK);
        return true;
    }

    void Stop()
    {
        if (clientFd >= 0) close(clientFd);
        if (listenFd >= 0) close(listenFd);
        clientFd = listenFd = -1;
    }

    bool Connected()
    {
        AcceptPending();
        return clientFd >= 0;
    }

    int Send(const u8* data, int len)
    {
        if (!Connected()) return 0;
        int sent = 0;
        while (sent < len)
        {
            ssize_t n = send(clientFd, data + sent, (size_t)(len - sent), MSG_NOSIGNAL);
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR))
            {
                pollfd p {clientFd, POLLOUT, 0};
                poll(&p, 1, 20);
                continue;
            }
            if (n <= 0) { DropClient(); return sent; }
            sent += (int)n;
        }
        return sent;
    }

    /// Collects one packet: everything that arrives until the link is quiet
    /// for kPacketGapUs.
    int Receive(u8* out, int maxLen)
    {
        if (!Connected()) return 0;
        int got = ReadAvailable(out, maxLen);
        if (got <= 0) return 0;
        auto lastByte = std::chrono::steady_clock::now();
        while (got < maxLen)
        {
            auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - lastByte).count();
            if (elapsed >= kPacketGapUs) break;
            pollfd p {clientFd, POLLIN, 0};
            int wait = (int)((kPacketGapUs - elapsed + 999) / 1000);
            if (poll(&p, 1, wait) <= 0) continue;
            int n = ReadAvailable(out + got, maxLen - got);
            if (n < 0) break;
            if (n > 0) { got += n; lastByte = std::chrono::steady_clock::now(); }
        }
        return got;
    }

private:
    int listenFd = -1;
    int clientFd = -1;

    void AcceptPending()
    {
        if (listenFd < 0) return;
        int fd = accept(listenFd, nullptr, nullptr);
        if (fd < 0) return;
        if (clientFd >= 0) close(clientFd); // newest peer wins
        int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
        clientFd = fd;
    }

    void DropClient()
    {
        if (clientFd >= 0) close(clientFd);
        clientFd = -1;
    }

    int ReadAvailable(u8* out, int maxLen)
    {
        ssize_t n = recv(clientFd, out, (size_t)maxLen, 0);
        if (n > 0) return (int)n;
        if (n == 0) { DropClient(); return -1; }
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return 0;
        DropClient();
        return -1;
    }
};

struct State
{
    std::mutex lock;
    Config config;
    VirtualPokewalker walker;
    TcpLink tcp;
    bool tcpRunning = false;
    u32 gamecode = 0;
    u32 irversion = 0;
    // The log file has its own lock so the emulator core can write to it
    // without taking `lock` (lock order: lock, then logLock).
    std::mutex logLock;
    FILE* logFile = nullptr;
    std::string lastCoreLine;
    int coreRepeats = 0;
    int coreLines = 0;
    s64 coreWindowStart = 0;
    int coreWindowLines = 0;
};

State& S()
{
    static State* state = new State();
    return *state;
}

// Caller holds s.logLock.
void WriteLogLocked(State& s, const std::string& line)
{
    if (!s.logFile) return;
    char stamp[32];
    time_t t = time(nullptr);
    struct tm lt {};
    localtime_r(&t, &lt);
    strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S", &lt);
    std::fprintf(s.logFile, "%s %s\n", stamp, line.c_str());
    std::fflush(s.logFile);
}

void WriteLog(State& s, const std::string& line)
{
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "Pokewalker", "%s", line.c_str());
#endif
    std::lock_guard<std::mutex> guard(s.logLock);
    WriteLogLocked(s, line);
}

const char* ModeName(Mode mode)
{
    switch (mode)
    {
    case Mode::Virtual: return "virtual Pokewalker";
    case Mode::Tcp: return "TCP";
    default: return "off";
    }
}

void LogPacket(State& s, const char* dir, const u8* data, int len)
{
    if (!s.config.logPackets || len <= 0) return;
    std::string line = std::string(dir) + " ";
    char hex[4];
    for (int i = 0; i < len; i++)
    {
        std::snprintf(hex, sizeof(hex), "%02X", data[i] ^ 0xAA);
        line += hex;
    }
    WriteLog(s, line);
}

bool VirtualActive(State& s)
{
    return s.config.mode == Mode::Virtual && s.walker.IsOpen() && IsPokewalkerGame(s.gamecode);
}

}

void Note(const std::string& text)
{
    WriteLog(S(), text);
}

void LogCoreMessage(bool error, const char* text)
{
    if (!text) return;
    State& s = S();
    std::string line(text);
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r' || line.back() == ' '))
        line.pop_back();
    if (line.empty()) return;
    std::lock_guard<std::mutex> guard(s.logLock);
    if (!s.logFile) return;
    if (line == s.lastCoreLine)
    {
        s.coreRepeats++;
        return;
    }
    if (s.coreRepeats > 0)
    {
        WriteLogLocked(s, "[melonDS] (previous message repeated " + std::to_string(s.coreRepeats) + " more times)");
        s.coreRepeats = 0;
    }
    s.lastCoreLine = line;
    // At most 20 lines a second and 2000 per run, so a chatty game can't fill the log.
    s64 now = (s64)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    if (now - s.coreWindowStart >= 1000) { s.coreWindowStart = now; s.coreWindowLines = 0; }
    if (s.coreWindowLines >= 20 || s.coreLines >= 2000)
    {
        if (s.coreLines == 2000) { WriteLogLocked(s, "[melonDS] (further core messages are not logged)"); s.coreLines++; }
        return;
    }
    s.coreWindowLines++;
    s.coreLines++;
    WriteLogLocked(s, std::string(error ? "[melonDS error] " : "[melonDS] ") + line);
}

bool IsPokewalkerGame(u32 gamecode)
{
    char c0 = (char)(gamecode & 0xFF), c1 = (char)((gamecode >> 8) & 0xFF), c2 = (char)((gamecode >> 16) & 0xFF);
    return c0 == 'I' && c1 == 'P' && (c2 == 'K' || c2 == 'G');
}

void Configure(const Config& config)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);

    if (config.logPath != s.config.logPath || !s.logFile)
    {
        std::lock_guard<std::mutex> logGuard(s.logLock);
        if (s.logFile) std::fclose(s.logFile);
        s.logFile = config.logPath.empty() ? nullptr : std::fopen(config.logPath.c_str(), "a");
    }
    bool modeChanged = config.mode != s.config.mode;
    s.config = config;
    s.walker.SetLogger([&s](const std::string& msg) { WriteLog(s, msg); });

    if (!config.eepromPath.empty() && (!s.walker.IsOpen() || s.walker.Path() != config.eepromPath))
    {
        std::string error;
        if (!s.walker.Open(config.eepromPath, &error))
            WriteLog(s, "Cannot open virtual Pokewalker: " + error);
    }
    static bool firstConfigure = true;
    if (firstConfigure || modeChanged)
    {
        WriteLog(s, std::string("Infrared mode: ") + ModeName(config.mode) +
                        (config.mode == Mode::Virtual ? (s.walker.IsOpen() ? " (ready)" : " (NOT open)") : ""));
        firstConfigure = false;
    }

    if (config.mode == Mode::Tcp)
    {
        if (!s.tcpRunning)
        {
            std::string error;
            s.tcpRunning = s.tcp.Start(config.tcpPort, &error);
            WriteLog(s, s.tcpRunning ? "IR over TCP listening on port " + std::to_string(config.tcpPort)
                                     : "IR over TCP failed: " + error);
        }
    }
    else if (s.tcpRunning)
    {
        s.tcp.Stop();
        s.tcpRunning = false;
    }
}

void OnCartReset(u32 gamecode, u32 irversion)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    s.gamecode = gamecode;
    s.irversion = irversion;
    s.walker.OnCartReset();
    char code[5] = {(char)(gamecode & 0xFF), (char)((gamecode >> 8) & 0xFF), (char)((gamecode >> 16) & 0xFF), (char)(gamecode >> 24), 0};
    for (int i = 0; i < 4; i++)
        if (code[i] < 0x20 || code[i] > 0x7E) code[i] = '?';
    std::string what;
    if (!IsPokewalkerGame(gamecode)) what = " (not HeartGold/SoulSilver: walker stays silent)";
    else if (s.config.mode != Mode::Virtual) what = std::string(" (infrared mode is ") + ModeName(s.config.mode) + ")";
    else if (!s.walker.IsOpen()) what = " (virtual Pokewalker could NOT be opened)";
    else what = " (virtual Pokewalker active)";
    WriteLog(s, std::string("Game loaded / reset, IR cartridge ") + code + what);
}

int OnGameSend(const u8* data, int len)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    LogPacket(s, "DS->", data, len);
    switch (s.config.mode)
    {
    case Mode::Virtual:
        if (VirtualActive(s)) s.walker.OnGameSend(data, (size_t)len);
        return len;
    case Mode::Tcp:
        if (len > 0 && data[0] == (0xF4 ^ 0xAA))
            std::this_thread::sleep_for(std::chrono::milliseconds(10)); // keep disconnect separate from the previous packet
        return s.tcp.Send(data, len);
    default:
        return 0;
    }
}

int OnGamePoll(u8* out, int maxLen)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    int n = 0;
    switch (s.config.mode)
    {
    case Mode::Virtual:
        if (VirtualActive(s)) n = s.walker.OnGamePoll(out, (size_t)maxLen);
        break;
    case Mode::Tcp:
        n = s.tcp.Receive(out, maxLen);
        break;
    default:
        break;
    }
    LogPacket(s, "<-PW", out, n);
    return n;
}

std::string StatusJson()
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    std::string json = s.walker.StatusJson();
    // splice the IR mode in front of the walker fields
    std::string prefix = "{\"mode\":" + std::to_string((int)s.config.mode) + ",\"gameIsPokewalkerCompatible\":" +
                         (IsPokewalkerGame(s.gamecode) ? "true" : "false") + ",";
    return prefix + json.substr(1);
}

bool AddSteps(u32 steps, int daysAgo, std::string* error)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    return s.walker.AddSteps(steps, daysAgo, error);
}

bool ResetWalker(std::string* error)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    return s.walker.ResetToFactory(error);
}

bool ImportImage(const std::vector<u8>& image, std::string* error)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    return s.walker.ImportImage(image, error);
}

bool RadarEncounter(int* slot, std::string* json, std::string* error)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    return s.walker.RadarEncounter(slot, json, error);
}

bool StoreCaught(int slot, std::string* error)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    return s.walker.StoreCaught(slot, error);
}

bool DowsingStart(u16* item, std::string* json, std::string* error)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    return s.walker.DowsingStart(item, json, error);
}

bool StoreDowsed(u16 item, std::string* error)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    return s.walker.StoreDowsed(item, error);
}

bool ExportImage(std::vector<u8>* image, std::string* error)
{
    State& s = S();
    std::lock_guard<std::mutex> guard(s.lock);
    if (!s.walker.IsOpen())
    {
        if (error) *error = "the virtual Pokewalker is not open";
        return false;
    }
    *image = s.walker.ExportImage();
    return true;
}

}
