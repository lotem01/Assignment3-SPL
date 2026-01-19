#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdlib>
#include <cctype>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "../include/ConnectionHandler.h"
#include "../include/StompClient.h"
#include "../include/event.h"

namespace
{

	struct ParsedFrame
	{
		std::string command;
		std::map<std::string, std::string> headers;
		std::string body;
	};

	struct ReportEvent
	{
		std::string user;
		std::string team_a;
		std::string team_b;
		std::string event_name;
		int time = 0;
		std::map<std::string, std::string> general_updates;
		std::map<std::string, std::string> team_a_updates;
		std::map<std::string, std::string> team_b_updates;
		std::string description;
	};

	struct StoredEvent
	{
		int time = 0;
		std::string name;
		std::string description;
		int half_index = 0;
		int seq = 0;
	};

	struct GameUserData
	{
		std::string team_a;
		std::string team_b;
		std::map<std::string, std::string> general_stats;
		std::map<std::string, std::string> team_a_stats;
		std::map<std::string, std::string> team_b_stats;
		std::vector<StoredEvent> events;
		bool halftime_occurred = false;
		int seq_counter = 0;
	};

	enum class ReceiptType
	{
		Join,
		Exit,
		Logout
	};

	struct ReceiptAction
	{
		ReceiptType type;
		std::string game;
	};

	struct ClientState
	{
		std::mutex mutex;
		std::condition_variable cv;
		std::unique_ptr<ConnectionHandler> handler;
		std::thread listener;
		std::atomic<bool> running{false};
		bool connected = false;
		bool logged_in = false;
		bool logout_in_progress = false;
		std::string username;
		int next_sub_id = 1;
		int next_receipt_id = 1;
		std::map<std::string, int> game_to_sub_id;
		std::map<int, ReceiptAction> receipt_actions;
		std::map<std::string, std::map<std::string, GameUserData>> game_user_data;
	};

	std::mutex cout_mutex;

	std::pair<std::string, std::string> split_key_value(const std::string &line)
	{
		size_t pos = line.find(':');
		if (pos > line.size())
		{
			return {line, ""};
		}
		std::string key = line.substr(0, pos);
		std::string value = line.substr(pos + 1);
		return {key, value};
	}

	ParsedFrame parse_frame(const std::string &frame)
	{
		ParsedFrame result;
		size_t first_newline = frame.find('\n');
		if (first_newline > frame.size())
		{
			result.command = frame;
			return result;
		}
		result.command = frame.substr(0, first_newline);
		size_t header_end = frame.find("\n\n", first_newline + 1);
		std::string headers_part;
		if (header_end > frame.size())
		{
			headers_part = frame.substr(first_newline + 1);
		}
		else
		{
			headers_part = frame.substr(first_newline + 1, header_end - (first_newline + 1));
			result.body = frame.substr(header_end + 2);
		}

		size_t line_start = 0;
		while (line_start <= headers_part.size())
		{
			size_t line_end = headers_part.find('\n', line_start);
			std::string line;
			if (line_end > headers_part.size())
			{
				line = headers_part.substr(line_start);
			}
			else
			{
				line = headers_part.substr(line_start, line_end - line_start);
			}
			if (line.empty())
			{
				if (line_end > headers_part.size())
				{
					break;
				}
				line_start = line_end + 1;
				continue;
			}
			auto kv = split_key_value(line);
			if (!kv.first.empty())
			{
				result.headers[kv.first] = kv.second;
			}
			if (line_end > headers_part.size())
			{
				break;
			}
			line_start = line_end + 1;
		}
		return result;
	}

	ReportEvent parse_report_body(const std::string &body)
	{
		ReportEvent report;
		enum class Section
		{
			None,
			General,
			TeamA,
			TeamB,
			Description
		};
		Section section = Section::None;
		std::vector<std::string> description_lines;

		size_t line_start = 0;
		while (line_start <= body.size())
		{
			size_t line_end = body.find('\n', line_start);
			std::string line;
			if (line_end > body.size())
			{
				line = body.substr(line_start);
			}
			else
			{
				line = body.substr(line_start, line_end - line_start);
			}
			if (section == Section::Description)
			{
				description_lines.push_back(line);
				if (line_end > body.size())
				{
					break;
				}
				line_start = line_end + 1;
				continue;
			}
			if (line.empty())
			{
				if (line_end > body.size())
				{
					break;
				}
				line_start = line_end + 1;
				continue;
			}
			auto kv = split_key_value(line);
			std::string key = kv.first;
			std::string value = kv.second;

			if (key == "user")
			{
				report.user = value;
				section = Section::None;
			}
			else if (key == "team a")
			{
				report.team_a = value;
				section = Section::None;
			}
			else if (key == "team b")
			{
				report.team_b = value;
				section = Section::None;
			}
			else if (key == "event name")
			{
				report.event_name = value;
				section = Section::None;
			}
			else if (key == "time")
			{
				report.time = std::atoi(value.c_str());
				section = Section::None;
			}
			else if (key == "general game updates")
			{
				section = Section::General;
			}
			else if (key == "team a updates")
			{
				section = Section::TeamA;
			}
			else if (key == "team b updates")
			{
				section = Section::TeamB;
			}
			else if (key == "description")
			{
				section = Section::Description;
			}
			else if (section == Section::General)
			{
				report.general_updates[key] = value;
			}
			else if (section == Section::TeamA)
			{
				report.team_a_updates[key] = value;
			}
			else if (section == Section::TeamB)
			{
				report.team_b_updates[key] = value;
			}
			if (line_end > body.size())
			{
				break;
			}
			line_start = line_end + 1;
		}

		if (!description_lines.empty())
		{
			std::string desc;
			for (size_t i = 0; i < description_lines.size(); ++i)
			{
				if (i > 0)
				{
					desc += "\n";
				}
				desc += description_lines[i];
			}
			report.description = desc;
		}
		return report;
	}

	bool is_false_value(const std::string &value)
	{
		std::string lowered;
		for (char ch : value)
		{
			lowered.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(ch))));
		}
		return lowered == "false" || lowered == "0";
	}

	bool is_true_value(const std::string &value)
	{
		std::string lowered;
		for (char ch : value)
		{
			lowered.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(ch))));
		}
		return lowered == "true" || lowered == "1";
	}

	void apply_report_event(GameUserData &data, const ReportEvent &report)
	{
		int half_index = data.halftime_occurred ? 1 : 0;

		for (const auto &entry : report.general_updates)
		{
			data.general_stats[entry.first] = entry.second;
			if (entry.first == "before halftime")
			{
				if (is_false_value(entry.second))
				{
					data.halftime_occurred = true;
				}
				else if (is_true_value(entry.second))
				{
					data.halftime_occurred = false;
				}
			}
		}
		for (const auto &entry : report.team_a_updates)
		{
			data.team_a_stats[entry.first] = entry.second;
		}
		for (const auto &entry : report.team_b_updates)
		{
			data.team_b_stats[entry.first] = entry.second;
		}

		StoredEvent stored;
		stored.time = report.time;
		stored.name = report.event_name;
		stored.description = report.description;
		stored.half_index = half_index;
		stored.seq = data.seq_counter++;
		data.events.push_back(stored);
	}

	std::string build_report_body(const std::string &user, const Event &event)
	{
		std::string body;
		body += "user: " + user +
				"\nteam a: " + event.get_team_a_name() +
				"\nteam b: " + event.get_team_b_name() +
				"\nevent name: " + event.get_name() +
				"\ntime: " + std::to_string(event.get_time()) +
				"\ngeneral game updates:\n";
		for (const auto &entry : event.get_game_updates())
		{
			body += entry.first + ": " + entry.second + "\n";
		}
		body += "team a updates:\n";
		for (const auto &entry : event.get_team_a_updates())
		{
			body += entry.first + ": " + entry.second + "\n";
		}
		body += "team b updates:\n";
		for (const auto &entry : event.get_team_b_updates())
		{
			body += entry.first + ": " + entry.second + "\n";
		}
		body += "description:\n";
		body += event.get_discription();
		return body;
	}

	std::string build_connect_frame(const std::string &user, const std::string &pass)
	{
		std::string frame;
		frame += "CONNECT\naccept-version:1.2\nhost:stomp.cs.bgu.ac.il\nlogin:" + user +
				 "\npasscode:" + pass + "\n\n";
		return frame;
	}

	std::string build_subscribe_frame(const std::string &game, int sub_id, int receipt_id)
	{
		std::string frame;
		frame += "SUBSCRIBE\ndestination:" + game +
				 "\nid:" + std::to_string(sub_id) +
				 "\nreceipt:" + std::to_string(receipt_id) + "\n\n";
		return frame;
	}

	std::string build_unsubscribe_frame(int sub_id, int receipt_id)
	{
		std::string frame;
		frame += "UNSUBSCRIBE\nid:" + std::to_string(sub_id) +
				 "\nreceipt:" + std::to_string(receipt_id) + "\n\n";
		return frame;
	}

	std::string build_disconnect_frame(int receipt_id)
	{
		std::string frame;
		frame += "DISCONNECT\nreceipt:" + std::to_string(receipt_id) + "\n\n";
		return frame;
	}

	std::string build_send_frame(const std::string &game, const std::string &body)
	{
		std::string frame;
		frame += "SEND\ndestination:" + game + "\n\n" + body;
		return frame;
	}

	void print_line(const std::string &line)
	{
		std::unique_lock<std::mutex> lock(cout_mutex);
		std::cout << line << std::endl;
	}

	void handle_receipt(ClientState &state, int receipt_id)
	{
		ReceiptAction action;
		std::unique_lock<std::mutex> lock(state.mutex);
		auto it = state.receipt_actions.find(receipt_id);
		if (it == state.receipt_actions.end())
		{
			return;
		}
		action = it->second;
		state.receipt_actions.erase(it);
		lock.unlock();
		if (action.type == ReceiptType::Join)
		{
			print_line("Joined channel " + action.game);
		}
		else if (action.type == ReceiptType::Exit)
		{
			print_line("Exited channel " + action.game);
		}
		else if (action.type == ReceiptType::Logout)
		{
			std::unique_lock<std::mutex> lock(state.mutex);
			state.logout_in_progress = false;
			state.logged_in = false;
			state.connected = false;
			state.running.store(false);
			auto *handler = state.handler.get();
			lock.unlock();
			if (handler != nullptr)
			{
				handler->close();
			}
			state.cv.notify_all();
		}
	}

	void process_message_frame(ClientState &state, const ParsedFrame &frame)
	{
		auto dest_it = frame.headers.find("destination");
		if (dest_it == frame.headers.end())
		{
			return;
		}
		std::string game = dest_it->second;
		ReportEvent report = parse_report_body(frame.body);
		if (report.user.empty())
		{
			return;
		}

		std::unique_lock<std::mutex> lock(state.mutex);
		GameUserData &data = state.game_user_data[game][report.user];
		if (data.team_a.empty())
		{
			data.team_a = report.team_a;
		}
		if (data.team_b.empty())
		{
			data.team_b = report.team_b;
		}
		apply_report_event(data, report);
	}

	void listener_loop(ClientState &state)
	{
		while (state.running.load())
		{
			std::string frame_data;
			std::unique_lock<std::mutex> lock(state.mutex);
			if (!state.handler)
			{
				state.running.store(false);
				state.connected = false;
				state.logged_in = false;
				state.logout_in_progress = false;
				state.cv.notify_all();
				break;
			}
			ConnectionHandler *handler = state.handler.get();
			lock.unlock();

			bool ok = handler->getFrameAscii(frame_data, '\0');
			if (!ok)
			{
				std::unique_lock<std::mutex> lock(state.mutex);
				state.running.store(false);
				state.connected = false;
				state.logged_in = false;
				state.logout_in_progress = false;
				state.cv.notify_all();
				break;
			}

			ParsedFrame frame = parse_frame(frame_data);
			if (frame.command == "CONNECTED")
			{
				std::unique_lock<std::mutex> lock(state.mutex);
				state.logged_in = true;
				lock.unlock();
				print_line("Login successful");
			}
			else if (frame.command == "MESSAGE")
			{
				process_message_frame(state, frame);
			}
			else if (frame.command == "RECEIPT")
			{
				auto it = frame.headers.find("receipt-id");
				if (it != frame.headers.end())
				{
					int receipt_id = std::atoi(it->second.c_str());
					handle_receipt(state, receipt_id);
				}
			}
			else if (frame.command == "ERROR")
			{
				std::string message = frame.headers.count("message") ? frame.headers.at("message") : "";
				if (message.find("Already logged in") <= message.size())
				{
					print_line("User already logged in");
				}
				else if (message.find("Wrong password") <= message.size())
				{
					print_line("Wrong password");
				}
				else if (!message.empty())
				{
					print_line(message);
				}
				else
				{
					print_line("Error received from server");
				}
				std::unique_lock<std::mutex> lock(state.mutex);
				state.running.store(false);
				state.connected = false;
				state.logged_in = false;
				state.logout_in_progress = false;
				auto *handler = state.handler.get();
				lock.unlock();
				if (handler != nullptr)
				{
					handler->close();
				}
				state.cv.notify_all();
				break;
			}
		}
	}

	bool send_frame(ClientState &state, const std::string &frame)
	{
		std::unique_lock<std::mutex> lock(state.mutex);
		if (!state.handler)
		{
			return false;
		}
		return state.handler->sendFrameAscii(frame, '\0');
	}

}

int StompClient::run()
{
	ClientState state;
	std::string line;

	while (std::getline(std::cin, line))
	{
		std::istringstream iss(line);
		std::string command;
		iss >> command;
		if (command.empty())
		{
			continue;
		}

		if (command == "login")
		{
			std::string host_port;
			std::string user;
			std::string pass;
			iss >> host_port >> user >> pass;
			if (host_port.empty() || user.empty() || pass.empty())
			{
				print_line("Usage: login {host:port} {username} {password}");
				continue;
			}

			std::unique_lock<std::mutex> lock1(state.mutex);
			if (state.connected || state.logged_in)
			{
				print_line("The client is already logged in, log out before trying again");
				continue;
			}
			lock1.unlock();

			size_t pos = host_port.find(':');
			if (pos > host_port.size())
			{
				print_line("Could not connect to server");
				continue;
			}
			std::string host = host_port.substr(0, pos);
			short port = static_cast<short>(std::atoi(host_port.substr(pos + 1).c_str()));

			std::unique_ptr<ConnectionHandler> handler(new ConnectionHandler(host, port));
			if (!handler->connect())
			{
				print_line("Could not connect to server");
				continue;
			}

			std::unique_lock<std::mutex> lock2(state.mutex);
			state.handler = std::move(handler);
			state.connected = true;
			state.logged_in = false;
			state.logout_in_progress = false;
			state.username = user;
			state.running.store(true);
			lock2.unlock();

			if (state.listener.joinable())
			{
				state.listener.join();
			}
			state.listener = std::thread(listener_loop, std::ref(state));

			std::string frame = build_connect_frame(user, pass);
			if (!send_frame(state, frame))
			{
				print_line("Could not connect to server");
				std::unique_lock<std::mutex> lock3(state.mutex);
				state.running.store(false);
				state.connected = false;
				state.logged_in = false;
				if (state.handler)
				{
					state.handler->close();
				}
				lock3.unlock();
				state.cv.notify_all();
			}
		}
		else if (command == "join")
		{
			std::string game;
			iss >> game;
			if (game.empty())
			{
				print_line("Usage: join {game_name}");
				continue;
			}
			std::unique_lock<std::mutex> lock1(state.mutex);
			if (!state.logged_in)
			{
				print_line("Please login first");
				continue;
			}
			if (state.game_to_sub_id.count(game) > 0)
			{
				continue;
			}
			lock1.unlock();

			int sub_id;
			int receipt_id;
			lock1.lock();
			sub_id = state.next_sub_id++;
			receipt_id = state.next_receipt_id++;
			state.game_to_sub_id[game] = sub_id;
			state.receipt_actions[receipt_id] = {ReceiptType::Join, game};
			lock1.unlock();
			std::string frame = build_subscribe_frame(game, sub_id, receipt_id);
			send_frame(state, frame);
		}
		else if (command == "exit")
		{
			std::string game;
			iss >> game;
			if (game.empty())
			{
				print_line("Usage: exit {game_name}");
				continue;
			}

			int sub_id = -1;
			int receipt_id = -1;
			std::unique_lock<std::mutex> lock1(state.mutex);
			if (!state.logged_in)
			{
				print_line("Please login first");
				continue;
			}
			auto it = state.game_to_sub_id.find(game);
			if (it == state.game_to_sub_id.end())
			{
				continue;
			}
			sub_id = it->second;
			state.game_to_sub_id.erase(it);
			receipt_id = state.next_receipt_id++;
			state.receipt_actions[receipt_id] = {ReceiptType::Exit, game};
			lock1.unlock();
			std::string frame = build_unsubscribe_frame(sub_id, receipt_id);
			send_frame(state, frame);
		}
		else if (command == "report")
		{
			std::string file_path;
			iss >> file_path;
			if (file_path.empty())
			{
				print_line("Usage: report {file}");
				continue;
			}

			std::string user;
			std::unique_lock<std::mutex> lock1(state.mutex);
			if (!state.logged_in)
			{
				print_line("Please login first");
				continue;
			}
			user = state.username;
			lock1.unlock();

			names_and_events nne = parseEventsFile(file_path);
			std::string game = nne.team_a_name + "_" + nne.team_b_name;

			lock1.lock();
			if (state.game_to_sub_id.count(game) == 0)
			{
				print_line("Not subscribed to channel " + game);
				continue;
			}
			lock1.unlock();

			for (const auto &event : nne.events)
			{
				std::string body = build_report_body(user, event);
				std::string frame = build_send_frame(game, body);
				send_frame(state, frame);

				ReportEvent report;
				report.user = user;
				report.team_a = event.get_team_a_name();
				report.team_b = event.get_team_b_name();
				report.event_name = event.get_name();
				report.time = event.get_time();
				report.general_updates = event.get_game_updates();
				report.team_a_updates = event.get_team_a_updates();
				report.team_b_updates = event.get_team_b_updates();
				report.description = event.get_discription();

				std::unique_lock<std::mutex> lock(state.mutex);
				GameUserData &data = state.game_user_data[game][user];
				if (data.team_a.empty())
				{
					data.team_a = report.team_a;
				}
				if (data.team_b.empty())
				{
					data.team_b = report.team_b;
				}
				apply_report_event(data, report);
			}
		}
		else if (command == "summary")
		{
			std::string game;
			std::string user;
			std::string output_file;
			iss >> game >> user >> output_file;
			if (game.empty() || user.empty() || output_file.empty())
			{
				print_line("Usage: summary {game_name} {user} {file}");
				continue;
			}

			std::map<std::string, std::string> general_stats;
			std::map<std::string, std::string> team_a_stats;
			std::map<std::string, std::string> team_b_stats;
			std::vector<StoredEvent> events;
			std::string team_a;
			std::string team_b;
			std::unique_lock<std::mutex> lock1(state.mutex);
			if (!state.logged_in)
			{
				print_line("Please login first");
				continue;
			}
			auto game_it = state.game_user_data.find(game);
			if (game_it == state.game_user_data.end())
			{
				print_line("No data for requested game/user");
				continue;
			}
			auto user_it = game_it->second.find(user);
			if (user_it == game_it->second.end())
			{
				print_line("No data for requested game/user");
				continue;
			}
			const GameUserData &data = user_it->second;
			team_a = data.team_a;
			team_b = data.team_b;
			general_stats = data.general_stats;
			team_a_stats = data.team_a_stats;
			team_b_stats = data.team_b_stats;
			events = data.events;
			lock1.unlock();

			std::sort(events.begin(), events.end(), [](const StoredEvent &a, const StoredEvent &b)
					  {
				if (a.half_index != b.half_index) {
					return a.half_index < b.half_index;
				}
				if (a.time != b.time) {
					return a.time < b.time;
				}
				return a.seq < b.seq; });

			std::ofstream out(output_file.c_str(), std::ofstream::trunc);
			if (!out)
			{
				print_line("Failed to open summary file");
				continue;
			}

			out << team_a << " vs " << team_b << "\n";
			out << "Game stats:\n";
			out << "General stats:\n";
			for (const auto &entry : general_stats)
			{
				out << entry.first << ": " << entry.second << "\n";
			}
			out << team_a << " stats:\n";
			for (const auto &entry : team_a_stats)
			{
				out << entry.first << ": " << entry.second << "\n";
			}
			out << team_b << " stats:\n";
			for (const auto &entry : team_b_stats)
			{
				out << entry.first << ": " << entry.second << "\n";
			}
			out << "Game event reports:\n";
			for (const auto &event : events)
			{
				out << event.time << " - " << event.name << ":\n";
				out << event.description << "\n";
			}
		}
		else if (command == "logout")
		{
			int receipt_id = -1;
			std::unique_lock<std::mutex> lock1(state.mutex);
			if (!state.logged_in || state.logout_in_progress)
			{
				continue;
			}
			state.logout_in_progress = true;
			receipt_id = state.next_receipt_id++;
			state.receipt_actions[receipt_id] = {ReceiptType::Logout, ""};
			lock1.unlock();
			std::string frame = build_disconnect_frame(receipt_id);
			send_frame(state, frame);

			std::unique_lock<std::mutex> lock2(state.mutex);
			state.cv.wait(lock2, [&state]()
						  { return !state.logout_in_progress; });
			lock2.unlock();

			if (state.listener.joinable())
			{
				state.listener.join();
			}
		}
		else
		{
			print_line("Unknown command");
		}
	}

	std::unique_lock<std::mutex> lock1(state.mutex);
	state.running.store(false);
	if (state.handler)
	{
		state.handler->close();
	}
	lock1.unlock();
	if (state.listener.joinable())
	{
		state.listener.join();
	}

	return 0;
}

int main()
{
	StompClient client;
	return client.run();
}
