import XCTest

final class FamilyOrbitUITests: XCTestCase {
    func testLoginHasNoInteractiveDemoData() {
        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Family Orbit"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.segmentedControls.buttons["新規登録"].exists)
        app.segmentedControls.buttons["新規登録"].tap()
        XCTAssertTrue(app.buttons["家族アカウントを作成"].exists)
        app.segmentedControls.buttons["ログイン"].tap()
        XCTAssertFalse(app.buttons["デモ画面を確認"].exists)
        XCTAssertFalse(app.staticTexts["佐藤ファミリー"].exists)
    }
}
