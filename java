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
        boolean tacacsRunning = isTacacsRunning();
        boolean radiusRunning = isRadiusRunning();

        model.addAttribute("tacacsRunning", tacacsRunning);
        model.addAttribute("radiusRunning", radiusRunning);
        model.addAttribute("logs", getRecentLogs());

        return "dashboard";
    }

    private boolean isTacacsRunning() throws IOException, InterruptedException {
        // Check if tac_plus container is running
        Process process = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", "tac_plus").start();
        Scanner scanner = new Scanner(process.getInputStream());
        if (scanner.hasNext()) {
            String result = scanner.next().trim();
            return result.equalsIgnoreCase("true");
        }
        return false;
    }

    private boolean isRadiusRunning() throws IOException, InterruptedException {
        // Check freeradius local service status
        Process process = new ProcessBuilder("systemctl", "is-active", "--quiet", "freeradius");
        int exitCode = process.start().waitFor();
        return exitCode == 0;
    }

    private String getRecentLogs() throws IOException {
        Process process = new ProcessBuilder("journalctl", "-n", "10", "--no-pager").start();
        Scanner scanner = new Scanner(process.getInputStream()).useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : "No logs available.";
    }

    @PostMapping("/restart/tacacs")
    public String restartTacacs() throws IOException, InterruptedException {
        new ProcessBuilder("docker", "restart", "tac_plus").start().waitFor();
        return "redirect:/";
    }

    @PostMapping("/restart/radius")
    public String restartRadius() throws IOException, InterruptedException {
        new ProcessBuilder("sudo", "systemctl", "restart", "freeradius").start().waitFor();
        return "redirect:/";
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
