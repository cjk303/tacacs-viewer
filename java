package com.example.tacconfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

@SpringBootApplication
public class TacConfigApplication {
    public static void main(String[] args) {
        SpringApplication.run(TacConfigApplication.class, args);
    }
}

@Controller
class DashboardController {

    @GetMapping("/")
    public String home(Model model) throws IOException, InterruptedException {
        boolean tacacsRunning = isServiceActive("tac_plus");
        boolean radiusRunning = isServiceActive("freeradius");

        model.addAttribute("tacacsRunning", tacacsRunning);
        model.addAttribute("radiusRunning", radiusRunning);
        model.addAttribute("tacacsValid", true); // Skip config validation for tacacs
        model.addAttribute("radiusValid", validateRadiusConfig());
        model.addAttribute("logs", getSystemLogs());

        return "dashboard";
    }

    @PostMapping("/restart/tacacs")
    public String restartTacacs() throws IOException, InterruptedException {
        restartService("tac_plus");
        return "redirect:/";
    }

    @PostMapping("/restart/radius")
    public String restartRadius() throws IOException, InterruptedException {
        restartService("freeradius");
        return "redirect:/";
    }

    private boolean isServiceActive(String service) throws IOException, InterruptedException {
        if ("tac_plus".equals(service)) {
            Process process = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", "tac_plus").start();
            Scanner scanner = new Scanner(process.getInputStream());
            if (scanner.hasNext()) {
                String result = scanner.next().trim();
                return result.equalsIgnoreCase("true");
            }
            return false;
        } else {
            Process process = new ProcessBuilder("systemctl", "is-active", service).start();
            return process.waitFor() == 0;
        }
    }

    private void restartService(String service) throws IOException, InterruptedException {
        if ("tac_plus".equals(service)) {
            new ProcessBuilder("docker", "restart", "tac_plus").start().waitFor();
        } else {
            new ProcessBuilder("sudo", "systemctl", "restart", service).start().waitFor();
        }
    }

    private boolean validateRadiusConfig() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("freeradius", "-CX").start();
        return process.waitFor() == 0;
    }

    private String getSystemLogs() throws IOException {
        Process process = new ProcessBuilder("journalctl", "-n", "10", "--no-pager").start();
        Scanner scanner = new Scanner(process.getInputStream()).useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : "No logs available.";
    }
}

@Controller
@RequestMapping("/tacacs")
class TacacsViewController {

    private static final String CONFIG_PATH = "/etc/tacacs/tac_plus.conf";

    @GetMapping("/edit")
    public String editConfig(Model model) throws IOException {
        String content = Files.readString(Path.of(CONFIG_PATH));
        model.addAttribute("configContent", content);
        return "tacacs_edit";
    }

    @PostMapping("/edit")
    public String saveConfig(@RequestParam("configContent") String newConfig, Model model) throws IOException {
        backupFile(CONFIG_PATH);
        Files.writeString(Path.of(CONFIG_PATH), newConfig);
        model.addAttribute("configContent", newConfig);
        model.addAttribute("message", "Configuration saved successfully.");
        return "tacacs_edit";
    }

    private void backupFile(String path) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Path source = Path.of(path);
        Path backup = Path.of(path + ".bak." + timestamp);
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
    }
}

@Controller
@RequestMapping("/radius")
class RadiusViewController {

    private static final String USERS_FILE = "/etc/freeradius/3.0/users";

    @GetMapping("/edit")
    public String editUsers(Model model) throws IOException {
        String content = Files.readString(Path.of(USERS_FILE));
        model.addAttribute("usersContent", content);
        return "radius_edit";
    }

    @PostMapping("/edit")
    public String saveUsers(@RequestParam("usersContent") String newContent, Model model) throws IOException {
        backupFile(USERS_FILE);
        Files.writeString(Path.of(USERS_FILE), newContent);
        model.addAttribute("usersContent", newContent);
        model.addAttribute("message", "Users file saved successfully.");
        return "radius_edit";
    }

    private void backupFile(String path) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Path source = Path.of(path);
        Path backup = Path.of(path + ".bak." + timestamp);
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
    }
}

@RestController
@RequestMapping("/api/tacacs")
class TacacsController {

    private static final String CONFIG_PATH = "/etc/tacacs/tac_plus.conf";

    @GetMapping("/config")
    public ResponseEntity<String> getConfig() throws IOException {
        String content = Files.readString(Path.of(CONFIG_PATH));
        return ResponseEntity.ok(content);
    }

    @PostMapping("/config")
    public ResponseEntity<String> updateConfig(@RequestBody String newConfig) throws IOException {
        Files.writeString(Path.of(CONFIG_PATH), newConfig);
        return ResponseEntity.ok("Configuration updated.");
    }
}

@RestController
@RequestMapping("/api/radius")
class RadiusController {

    private static final String USERS_FILE = "/etc/freeradius/3.0/users";

    @GetMapping("/users")
    public ResponseEntity<String> getUsersFile() throws IOException {
        return ResponseEntity.ok(Files.readString(Path.of(USERS_FILE)));
    }

    @PostMapping("/users")
    public ResponseEntity<String> updateUsersFile(@RequestBody String newContent) throws IOException {
        Files.writeString(Path.of(USERS_FILE), newContent);
        return ResponseEntity.ok("Users file updated.");
    }
}

@Configuration
class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf().disable()
            .authorizeHttpRequests()
            .anyRequest().authenticated()
            .and()
            .httpBasic();
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails admin = User.withDefaultPasswordEncoder()
            .username("admin")
            .password("admin123")
            .roles("ADMIN")
            .build();
        return new InMemoryUserDetailsManager(admin);
    }
}
