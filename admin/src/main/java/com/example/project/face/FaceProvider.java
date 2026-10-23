package com.example.project.face;
import java.util.List;
public interface FaceProvider {
    String id();
    String modelVersion();
    String enroll(List<String> images);
    double compare(String payload, String image);
    double threshold();
    boolean ready();
}
