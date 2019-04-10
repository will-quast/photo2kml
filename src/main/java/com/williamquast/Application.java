package com.williamquast;


import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;

public class Application extends javafx.application.Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        log.info("Starting Application.");
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            FXMLLoader loader = new FXMLLoader();
            Parent root = loader.load(getClass().getResourceAsStream("/main.fxml"));

            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            int height = screenSize.height * 2 / 3;
            int width = screenSize.width * 2 / 3;
            Scene scene = new Scene(root, width, height);

            primaryStage.setTitle("Photo2Kml");
            primaryStage.getIcons().add(new Image(getClass().getResourceAsStream("/app.png")));
            primaryStage.setScene(scene);
            primaryStage.show();

        } catch (IOException ex) {
            log.error("Failed to load application GUI.", ex);
        }
    }


}
