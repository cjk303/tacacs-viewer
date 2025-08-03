import os
import shutil
import glob
from datetime import datetime
from flask import Flask, render_template, request, redirect, url_for, flash

app = Flask(__name__)
app.secret_key = 'supersecretkey'

# Config paths
TACACS_CONF = '/etc/tacacs/tacacs.conf'
FREERADIUS_CONF = '/etc/freeradius/clients.conf'
BACKUP_DIR = '/etc/tacacs/backups'

os.makedirs(BACKUP_DIR, exist_ok=True)

# Index route
@app.route('/')
def index():
    return render_template('index.html')

# Backup routes
@app.route('/backups')
def backups():
    tacacs_backups = sorted(
        glob.glob(os.path.join(BACKUP_DIR, 'tacacs.conf.bak.*')),
        reverse=True,
    )
    freeradius_backups = sorted(
        glob.glob(os.path.join(BACKUP_DIR, 'freeradius.clients.conf.bak.*')),
        reverse=True,
    )
    return render_template(
        'backups.html',
        tacacs_backups=tacacs_backups,
        freeradius_backups=freeradius_backups,
    )

@app.route('/backups/restore', methods=['POST'])
def restore_backup():
    file_path = request.form.get('file_path')
    if not file_path or not os.path.exists(file_path):
        flash('Backup file not found.', 'error')
        return redirect(url_for('backups'))

    try:
        if 'tacacs.conf.bak' in file_path:
            shutil.copy2(file_path, TACACS_CONF)
        elif 'freeradius.clients.conf.bak' in file_path:
            shutil.copy2(file_path, FREERADIUS_CONF)
        else:
            flash('Invalid backup file.', 'error')
            return redirect(url_for('backups'))
        flash(f'Restored backup: {os.path.basename(file_path)}', 'success')
    except Exception as e:
        flash(f'Error restoring backup: {e}', 'error')
    return redirect(url_for('backups'))

@app.route('/backups/delete', methods=['POST'])
def delete_backup():
    file_path = request.form.get('file_path')
    if not file_path or not os.path.exists(file_path):
        flash('Backup file not found.', 'error')
        return redirect(url_for('backups'))
    try:
        os.remove(file_path)
        flash(f'Deleted backup: {os.path.basename(file_path)}', 'success')
    except Exception as e:
        flash(f'Error deleting backup: {e}', 'error')
    return redirect(url_for('backups'))

# Users & Groups management
@app.route('/users_groups', methods=['GET', 'POST'])
def users_groups():
    if request.method == 'POST':
        updated_config = request.form.get('config')
        try:
            backup_file = os.path.join(
                BACKUP_DIR,
                f'tacacs.conf.bak.{datetime.now().strftime("%Y%m%d%H%M%S")}',
            )
            shutil.copy2(TACACS_CONF, backup_file)
            with open(TACACS_CONF, 'w') as f:
                f.write(updated_config)
            flash('Users & Groups config saved with backup.', 'success')
        except Exception as e:
            flash(f'Error saving Users & Groups config: {e}', 'error')
        return redirect(url_for('users_groups'))

    with open(TACACS_CONF) as f:
        config = f.read()
    return render_template('users_groups.html', config=config, title='Users & Groups')

# Restart TACACS (docker container)
@app.route('/restart_tacacs', methods=['POST'])
def restart_tacacs():
    try:
        os.system('docker restart tacacs_plus')
        flash('TACACS+ container restarted.', 'success')
    except Exception as e:
        flash(f'Error restarting TACACS+: {e}', 'error')
    return redirect(url_for('index'))

# Restart FreeRADIUS (systemctl)
@app.route('/restart_freeradius', methods=['POST'])
def restart_freeradius():
    try:
        os.system('systemctl restart freeradius')
        flash('FreeRADIUS service restarted.', 'success')
    except Exception as e:
        flash(f'Error restarting FreeRADIUS: {e}', 'error')
    return redirect(url_for('index'))

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0')
