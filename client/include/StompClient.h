#pragma once

class StompClient {
public:
	StompClient() = default;
	int run();
	StompClient(const StompClient &) = delete;
	StompClient &operator=(const StompClient &) = delete;
};
