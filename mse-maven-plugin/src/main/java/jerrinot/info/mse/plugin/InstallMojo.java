package jerrinot.info.mse.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Path;
import java.nio.file.Paths;

@Mojo(name = "install", requiresProject = false)
public class InstallMojo extends AbstractMojo {

    @Parameter(property = "mse.projectDir", defaultValue = "${user.dir}")
    private String projectDir;

    @Parameter(property = "mse.version", defaultValue = "${plugin.version}")
    private String version;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            Path dir = Paths.get(projectDir);
            ExtensionsXmlManager manager = new ExtensionsXmlManager();
            boolean modified = manager.installExtension(dir, version);
            if (modified) {
                getLog().info("MSE " + version + " configured in .mvn/extensions.xml");
            } else {
                getLog().info("MSE " + version + " already configured in .mvn/extensions.xml");
            }
            getLog().info("Activate with: mvn -Dmse clean verify");
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to install MSE extension", e);
        }
    }
}
