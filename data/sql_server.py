#!/usr/bin/env python3
"""
Basic Python Server for STOMP Assignment – Stage 3.3

IMPORTANT:
DO NOT CHANGE the server name or the basic protocol.
Students should EXTEND this server by implementing
the methods below.
"""

import socket
import sqlite3
import sys
import threading


SERVER_NAME = "STOMP_PYTHON_SQL_SERVER"  # DO NOT CHANGE!
DB_FILE = "stomp_server.db"              # DO NOT CHANGE!

db_lock = threading.Lock()
db_conn = None


def recv_null_terminated(sock: socket.socket) -> str:
    data = b""
    while True:
        chunk = sock.recv(1024)
        if not chunk:
            return ""
        data += chunk
        if b"\0" in data:
            msg, _ = data.split(b"\0", 1)
            return msg.decode("utf-8", errors="replace")


def init_database():
    global db_conn
    db_conn = sqlite3.connect(DB_FILE, check_same_thread=False)
    with db_lock:
        cur = db_conn.cursor()
        cur.execute("PRAGMA foreign_keys = ON")
        cur.execute(
            "CREATE TABLE IF NOT EXISTS users ("
            "username TEXT PRIMARY KEY, "
            "password TEXT NOT NULL, "
            "registration_date TEXT NOT NULL)"
        )
        cur.execute(
            "CREATE TABLE IF NOT EXISTS login_history ("
            "username TEXT NOT NULL, "
            "login_time TEXT NOT NULL, "
            "logout_time TEXT, "
            "PRIMARY KEY(username, login_time), "
            "FOREIGN KEY(username) REFERENCES users(username) ON DELETE CASCADE)"
        )
        cur.execute(
            "CREATE TABLE IF NOT EXISTS file_tracking ("
            "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            "username TEXT NOT NULL, "
            "filename TEXT NOT NULL, "
            "upload_time TEXT NOT NULL, "
            "game_channel TEXT NOT NULL, "
            "FOREIGN KEY(username) REFERENCES users(username) ON DELETE CASCADE)"
        )
        db_conn.commit()


def execute_sql_command(sql_command: str) -> str:
    if db_conn is None:
        return "ERROR:Database not initialized"
    with db_lock:
        try:
            db_conn.execute(sql_command)
            db_conn.commit()
            return "SUCCESS"
        except Exception as e:
            return f"ERROR:{e}"


def execute_sql_query(sql_query: str) -> str:
    if db_conn is None:
        return "ERROR:Database not initialized"
    with db_lock:
        try:
            cur = db_conn.execute(sql_query)
            rows = cur.fetchall()
            if not rows:
                return "SUCCESS"
            parts = ["SUCCESS"]
            for row in rows:
                parts.append(str(tuple(row)))
            return "|".join(parts)
        except Exception as e:
            return f"ERROR:{e}"


def handle_client(client_socket: socket.socket, addr):
    print(f"[{SERVER_NAME}] Client connected from {addr}")

    try:
        while True:
            message = recv_null_terminated(client_socket)
            if message == "":
                break

            print(f"[{SERVER_NAME}] Received:")
            print(message)
            stripped = message.strip()
            if stripped.upper().startswith("SELECT"):
                response = execute_sql_query(stripped)
            else:
                response = execute_sql_command(stripped)

            client_socket.sendall((response + "\0").encode("utf-8"))

    except Exception as e:
        print(f"[{SERVER_NAME}] Error handling client {addr}: {e}")
    finally:
        try:
            client_socket.close()
        except Exception:
            pass
        print(f"[{SERVER_NAME}] Client {addr} disconnected")


def start_server(host="127.0.0.1", port=7778):
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server_socket.bind((host, port))
        server_socket.listen(5)
        print(f"[{SERVER_NAME}] Server started on {host}:{port}")
        print(f"[{SERVER_NAME}] Waiting for connections...")

        while True:
            client_socket, addr = server_socket.accept()
            t = threading.Thread(
                target=handle_client,
                args=(client_socket, addr),
                daemon=True
            )
            t.start()

    except KeyboardInterrupt:
        print(f"\n[{SERVER_NAME}] Shutting down server...")
    finally:
        try:
            server_socket.close()
        except Exception:
            pass


if __name__ == "__main__":
    port = 7778
    if len(sys.argv) > 1:
        raw_port = sys.argv[1].strip()
        try:
            port = int(raw_port)
        except ValueError:
            print(f"Invalid port '{raw_port}', falling back to default {port}")

    init_database()
    start_server(port=port)
