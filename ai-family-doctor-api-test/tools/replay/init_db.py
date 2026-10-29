#!/usr/bin/env python3
"""Create only ai_family_doctor_replay and its account. Never resets an existing root password."""
import argparse
import getpass
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import subprocess
import tempfile
import urllib.parse

ROOT = Path(__file__).resolve().parents[3]
ENV = ROOT / '.env.replay'
DATABASE = 'ai_family_doctor_replay'
USER = 'family_replay'

def new_password():
    # 保证满足本机 MySQL MEDIUM 的大小写、数字、特殊字符要求。
    return secrets.token_urlsafe(32) + 'Aa7!'

def dev_value(key):
    text = (ROOT / 'ai-family-doctor-server/src/main/resources/application-dev.yml').read_text()
    match = re.search(r'^\s*' + key + r':\s*(.+)$', text, re.M)
    if not match:
        raise RuntimeError('开发配置缺少所需连接字段')
    value = match.group(1).strip().strip('"\'')
    variable = re.fullmatch(r'\$\{([^:}]+)(?::(.*))?\}', value)
    return os.getenv(variable.group(1), variable.group(2) or '') if variable else value

def client_quote(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n') + '"'

def mysql(host, port, username, password, sql, database=None):
    client = shutil.which('mysql')
    if not client:
        raise RuntimeError('未找到 mysql 客户端')
    with tempfile.NamedTemporaryFile(mode='w', prefix='family-replay-mysql-', delete=False) as file:
        name = file.name
        os.chmod(name, 0o600)
        file.write('[client]\n' + '\n'.join(f'{k}={client_quote(v)}' for k, v in
                   dict(host=host, port=port, user=username, password=password, protocol='tcp').items()) + '\n')
    try:
        command = [client, '--defaults-extra-file=' + name, '--connect-timeout=3', '--batch', '--skip-column-names']
        if database:
            command.append(database)
        result = subprocess.run(command, input=sql, text=True, capture_output=True, timeout=30)
        if result.returncode:
            code = re.search(r'ERROR (\d+)', result.stderr)
            raise RuntimeError('MySQL 操作失败，错误码 ' + (code.group(1) if code else 'unknown'))
        return result.stdout.strip()
    finally:
        os.unlink(name)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--use-dev-credentials', action='store_true', help='使用本机开发配置中的凭证，不打印或保存管理员密码')
    args = parser.parse_args()
    host = os.getenv('REPLAY_DB_HOST', '127.0.0.1')
    port = int(os.getenv('REPLAY_DB_PORT', '3306'))
    admin = os.getenv('REPLAY_MYSQL_ADMIN_USER', 'root')
    if args.use_dev_credentials:
        url = urllib.parse.urlparse(dev_value('url').removeprefix('jdbc:'))
        host, port = url.hostname, url.port or 3306
        admin, password = dev_value('username'), dev_value('password')
    else:
        password = os.getenv('REPLAY_MYSQL_ADMIN_PASSWORD')
        if password is None:
            password = getpass.getpass('本机 MySQL 管理员密码：')
    if host not in ('localhost', '127.0.0.1'):
        raise RuntimeError('初始化仅允许本机数据库')
    probe = mysql(host, port, admin, password,
        "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='ai_family_doctor_replay';"
        "SELECT COUNT(*) FROM mysql.user WHERE User='family_replay';")
    if ENV.exists():
        values = {}
        for line in ENV.read_text().splitlines():
            if '=' in line and not line.startswith('#'):
                key, value = line.split('=', 1)
                parts = shlex.split(value)
                values[key] = parts[0] if parts else ''
        if values.get('REPLAY_DB_HOST') != host or values.get('REPLAY_DB_PORT') != str(port):
            raise RuntimeError('现有 .env.replay 的数据库地址不同，不自动修改')
        replay_password = values['REPLAY_DB_PASSWORD']
        if not re.fullmatch(r'[A-Za-z0-9_!@%+=.-]{20,}', replay_password):
            raise RuntimeError('回放密码格式异常，不自动修改账号')
    else:
        if any(int(row) for row in probe.splitlines()):
            raise RuntimeError('发现已存在的同名回放库或账号但无 .env.replay，不自动接管或重置')
        replay_password = new_password()
        values = dict(REPLAY_DB_HOST=host, REPLAY_DB_PORT=str(port), REPLAY_DB_PASSWORD=replay_password,
                      REPLAY_JWT_SECRET=secrets.token_hex(32), REPLAY_PORT='18080')
        # 在创建前保存随机凭证，失败重试也不遗失已创建账号的密码。
        fd = os.open(ENV, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, 'w') as file:
            file.write('# 本机回放凭证；不要提交到仓库\n')
            file.write('\n'.join(f'{key}={shlex.quote(value)}' for key, value in values.items()) + '\n')
    if int(probe.splitlines()[1]) == 0:
        # 只在专用账号尚未创建时更新随机凭证；绝不重置已存在账号。
        replay_password = new_password()
        values['REPLAY_DB_PASSWORD'] = replay_password
        with tempfile.NamedTemporaryFile(mode='w', dir=ROOT, prefix='.env.replay.', delete=False) as file:
            temporary = file.name
            os.chmod(temporary, 0o600)
            file.write('# 本机回放凭证；不要提交到仓库\n')
            file.write('\n'.join(f'{key}={shlex.quote(value)}' for key, value in values.items()) + '\n')
        os.replace(temporary, ENV)
    sql = f'CREATE DATABASE IF NOT EXISTS `{DATABASE}` CHARACTER SET utf8mb4;\nUSE `{DATABASE}`;\n'
    sql += (Path(__file__).with_name('schema.sql')).read_text() + '\n'
    for account_host in ('localhost', '127.0.0.1'):
        sql += f"CREATE USER IF NOT EXISTS '{USER}'@'{account_host}' IDENTIFIED BY '{replay_password}';\n"
        sql += f"GRANT SELECT,INSERT,UPDATE,DELETE ON `{DATABASE}`.* TO '{USER}'@'{account_host}';\n"
    mysql(host, port, admin, password, sql)
    if mysql(host, port, USER, replay_password, 'SELECT DATABASE();', DATABASE) != DATABASE:
        raise RuntimeError('回放账号验证失败')
    print('回放库和受限账号初始化成功；凭证保存在 .env.replay（0600），未修改 root 或开发库。')

if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, KeyError, ValueError, subprocess.TimeoutExpired) as error:
        if isinstance(error, RuntimeError):
            print(str(error))
        else:
            print('初始化失败，请核对本机连接配置和 .env.replay；未重置管理员密码。')
        raise SystemExit(1)
