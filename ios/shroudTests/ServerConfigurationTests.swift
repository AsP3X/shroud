import Foundation
import Testing
@testable import shroud

struct ServerConfigurationTests {
    @Test
    func officialUsesManagedURL() {
        var config = ServerConfiguration.default
        config.mode = .official
        #expect(ServerConfiguration.officialBaseURLString == "https://shroud-app.com/api/v1")
        #expect(config.resolvedBaseURLString == ServerConfiguration.officialBaseURLString)
    }

    @Test
    func selfHostedComposesHostPortAndPath() {
        var config = ServerConfiguration.default
        config.mode = .selfHosted
        config.host = "192.168.1.20"
        config.port = "8080"
        config.apiPath = "api/v1"
        config.useHTTPS = false
        #expect(config.resolvedBaseURLString == "http://192.168.1.20:8080/api/v1")
    }

    @Test
    func selfHostedHTTPSOmitsDefaultPortStyleStillIncludesPortWhenSet() {
        var config = ServerConfiguration.default
        config.mode = .selfHosted
        config.host = "chat.example.com"
        config.port = "443"
        config.apiPath = "/api/v1"
        config.useHTTPS = true
        #expect(config.resolvedBaseURLString == "https://chat.example.com:443/api/v1")
    }

    @Test
    func validationRejectsEmptyHost() {
        var config = ServerConfiguration.default
        config.mode = .selfHosted
        config.host = "  "
        #expect(config.validationError() != nil)
    }

    @Test
    func validationRejectsBadPort() {
        var config = ServerConfiguration.default
        config.mode = .selfHosted
        config.host = "127.0.0.1"
        config.port = "99999"
        #expect(config.validationError() != nil)
    }

    @Test
    func addressLabelNamesTheOfficialServerOrTheHost() {
        var config = ServerConfiguration.default
        config.mode = .official
        #expect(config.addressLabel == "Official Shroud server")
        config.mode = .selfHosted
        config.host = " chat.example.com "
        config.port = "8443"
        #expect(config.addressLabel == "chat.example.com")
        config.host = ""
        config.useHTTPS = true
        config.apiPath = "/api/v1"
        #expect(config.addressLabel == "https://…/api/v1")
    }
}
