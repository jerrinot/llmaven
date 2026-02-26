package jerrinot.info.mse.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Path;
import java.nio.file.Paths;

@Mojo(name = "uninstall", requiresProject = false)
public class UninstallMojo extends AbstractMojo {

    @Parameter(property = "mse.projectDir", defaultValue = "${user.dir}")
    private String projectDir;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            Path dir = Paths.get(projectDir);
            ExtensionsXmlManager manager = new ExtensionsXmlManager();
            boolean removed = manager.uninstallExtension(dir);
            if (removed) {
                getLog().info("MSE removed from .mvn/extensions.xml");
            } else {
                getLog().info("MSE was not found in .mvn/extensions.xml");
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to uninstall MSE extension", e);
        }
    }
}
